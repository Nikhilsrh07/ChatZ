import unittest
from types import SimpleNamespace

from fastapi import HTTPException
from pydantic import ValidationError
from starlette.requests import Request

from app import MessagePayload, require_sync_token, store_message


class MessagePayloadTests(unittest.TestCase):
    def test_accepts_valid_message(self) -> None:
        message = MessagePayload(
            id="message-1",
            body="Hello",
            created_at=1,
            direction="outgoing",
            transport="Bluetooth",
        )
        self.assertEqual(message.body, "Hello")

    def test_accepts_message_at_byte_limit(self) -> None:
        message = MessagePayload(
            id="message-2",
            body="x" * (64 * 1024),
            created_at=1,
            direction="incoming",
            transport="Wi-Fi / tethered network",
        )
        self.assertEqual(len(message.body.encode("utf-8")), 64 * 1024)

    def test_rejects_message_over_byte_limit(self) -> None:
        with self.assertRaises(ValidationError):
            MessagePayload(
                id="message-3",
                body="x" * (64 * 1024 + 1),
                created_at=1,
                direction="incoming",
                transport="Bluetooth",
            )

    def test_limit_is_measured_in_utf8_bytes(self) -> None:
        with self.assertRaises(ValidationError):
            MessagePayload(
                id="message-unicode",
                body="€" * 21_846,
                created_at=1,
                direction="incoming",
                transport="Bluetooth",
            )

    def test_rejects_line_breaks(self) -> None:
        with self.assertRaises(ValidationError):
            MessagePayload(
                id="message-4",
                body="first\nsecond",
                created_at=1,
                direction="outgoing",
                transport="Bluetooth",
            )

    def test_rejects_unexpected_fields(self) -> None:
        with self.assertRaises(ValidationError):
            MessagePayload(
                id="message-5",
                body="Hello",
                created_at=1,
                direction="outgoing",
                transport="Bluetooth",
                private_field="unexpected",
            )

    def test_requires_matching_bearer_token(self) -> None:
        request = Request(
            {
                "type": "http",
                "app": SimpleNamespace(state=SimpleNamespace(sync_token="test-token")),
            }
        )
        require_sync_token(request, "Bearer test-token")

    def test_rejects_missing_or_incorrect_bearer_token(self) -> None:
        request = Request(
            {
                "type": "http",
                "app": SimpleNamespace(state=SimpleNamespace(sync_token="test-token")),
            }
        )
        for header in (None, "Basic test-token", "Bearer wrong-token"):
            with self.subTest(header=header):
                with self.assertRaises(HTTPException) as raised:
                    require_sync_token(request, header)
                self.assertEqual(raised.exception.status_code, 401)

    def test_message_write_is_idempotent(self) -> None:
        payload = MessagePayload(
            id="message-6",
            body="Hello",
            created_at=1,
            direction="outgoing",
            transport="Bluetooth",
        )
        collection = SimpleNamespace(
            update_one=lambda query, update, upsert: SimpleNamespace(upserted_id=None)
        )
        response = store_message(payload, collection, None)
        self.assertTrue(response["duplicate"])
        self.assertTrue(response["stored"])


if __name__ == "__main__":
    unittest.main()
