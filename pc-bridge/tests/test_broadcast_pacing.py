"""Broadcasts skip the duplicate-blast guard but are paced and capped."""
import wa_bridge


def test_broadcast_slots_are_paced_and_capped(monkeypatch):
    slept = []
    monkeypatch.setattr(wa_bridge.time, "sleep", lambda s: slept.append(s))
    monkeypatch.setattr(wa_bridge, "BROADCAST_GAP_SEC", 8)
    monkeypatch.setattr(wa_bridge, "BROADCAST_DAILY_CAP", 3)
    wa_bridge._broadcast_sends.clear()
    assert [wa_bridge._broadcast_slot() for _ in range(4)] == [True, True, True, False]
    assert len(slept) == 2 and all(8 <= s <= 12.1 for s in slept)   # gap + up to 50% jitter
    wa_bridge._broadcast_sends.clear()


def test_plain_messages_still_get_the_duplicate_guard():
    wa_bridge._recent_outbound_sends.clear()
    wa_bridge._record_outbound_send("Hello there", "Mrs Moyo")
    assert wa_bridge._looks_like_duplicate_blast("hello  there", "Mr Dube")
    assert not wa_bridge._looks_like_duplicate_blast("hello there", "Mrs Moyo")
    wa_bridge._recent_outbound_sends.clear()
