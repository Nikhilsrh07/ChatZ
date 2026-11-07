import os
import secrets
from contextlib import asynccontextmanager
from typing import Annotated, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pymongo import ASCENDING, DESCENDING, MongoClient
from pymongo.collection import Collection
from pymongo.errors import PyMongoError

MAX_MESSAGE_BYTES = 64 * 1024


class MessagePayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1, max_length=64)
    body: str = Field(min_length=1)
    created_at: int = Field(gt=0)
    direction: Literal["outgoing", "incoming"]
    transport: Literal["Wi-Fi / tethered network", "Bluetooth"]
    conversation_id: str = Field(default="direct", min_length=1, max_length=64)
    conversation_name: str = Field(default="Direct", min_length=1, max_length=48)
    conversation_type: Literal["direct", "group", "channel"] = "direct"

    @field_validator("body")
    @classmethod
    def validate_body(cls, body: str) -> str:
        if "\n" in body or "\r" in body:
            raise ValueError("Messages must be single-line text")
        if len(body.encode("utf-8")) > MAX_MESSAGE_BYTES:
            raise ValueError("Message exceeds the 64 KiB limit")
        return body


@asynccontextmanager
async def lifespan(application: FastAPI):
    mongo_uri = os.environ.get("MONGODB_URI", "").strip()
    sync_token = os.environ.get("CHATZ_SYNC_TOKEN", "").strip()
    if not mongo_uri:
        raise RuntimeError("MONGODB_URI must be configured")
    if len(sync_token) < 32:
        raise RuntimeError("CHATZ_SYNC_TOKEN must contain at least 32 characters")

    client = MongoClient(mongo_uri, serverSelectionTimeoutMS=5_000)
    try:
        client.admin.command("ping")
        collection = client["chatz"]["messages"]
        collection.create_index([("id", ASCENDING)], unique=True)
        collection.create_index([("created_at", DESCENDING)])
        collection.create_index([("conversation_id", ASCENDING), ("created_at", DESCENDING)])
    except PyMongoError as error:
        client.close()
        raise RuntimeError("Unable to initialize the MongoDB message store") from error

    application.state.mongo_client = client
    application.state.messages = collection
    application.state.sync_token = sync_token
    try:
        yield
    finally:
        client.close()


app = FastAPI(title="ChatZ sync API", version="1.0.0", lifespan=lifespan)


def require_sync_token(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    expected = request.app.state.sync_token
    scheme, separator, supplied = (authorization or "").partition(" ")
    if (
        not separator
        or scheme.lower() != "bearer"
        or not secrets.compare_digest(supplied, expected)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="A valid bearer token is required",
            headers={"WWW-Authenticate": "Bearer"},
        )


def get_message_collection(request: Request) -> Collection:
    return request.app.state.messages


@app.get("/healthz")
def health(request: Request) -> dict[str, str]:
    try:
        request.app.state.mongo_client.admin.command("ping")
    except PyMongoError as error:
        raise HTTPException(status_code=503, detail="MongoDB is unavailable") from error
    return {"status": "ok"}


@app.post("/api/messages", status_code=status.HTTP_201_CREATED)
def store_message(
    payload: MessagePayload,
    collection: Annotated[Collection, Depends(get_message_collection)],
    _: Annotated[None, Depends(require_sync_token)],
) -> dict[str, str | bool]:
    try:
        result = collection.update_one(
            {"id": payload.id},
            {"$setOnInsert": payload.model_dump()},
            upsert=True,
        )
    except PyMongoError as error:
        raise HTTPException(status_code=503, detail="Unable to store message") from error
    return {"id": payload.id, "stored": True, "duplicate": result.upserted_id is None}


@app.get("/api/messages")
def list_messages(
    collection: Annotated[Collection, Depends(get_message_collection)],
    _: Annotated[None, Depends(require_sync_token)],
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
) -> list[dict[str, str | int]]:
    try:
        documents = collection.find(
            {},
            {
                "_id": 0,
                "id": 1,
                "body": 1,
                "created_at": 1,
                "direction": 1,
                "transport": 1,
                "conversation_id": 1,
                "conversation_name": 1,
                "conversation_type": 1,
            },
        ).sort("created_at", DESCENDING).limit(limit)
        return list(documents)
    except PyMongoError as error:
        raise HTTPException(status_code=503, detail="Unable to read messages") from error
