"""Reproduce Kotlin test fixture using the authoritative MIT py-fsrs==6.3.2 package.
Install with: python -m pip install fsrs==6.3.2
"""
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path
import json
from fsrs import Card, Rating, Scheduler, State

assert version("fsrs") == "6.3.2"
base = datetime(2026, 1, 1, tzinfo=timezone.utc)
def stamp(t):
    return None if t is None else round(t.timestamp() * 1000)
def schedule(c):
    return dict(state={1:"LEARNING",2:"REVIEW",3:"RELEARNING"}[c.state.value],
                step=c.step, stability=c.stability, difficulty=c.difficulty,
                due=stamp(c.due), lastReview=stamp(c.last_review))
rows = []
for retention in (.7, .9, .97):
    scheduler = Scheduler(desired_retention=retention, enable_fuzzing=False)
    for state, step, stability, difficulty, elapsed in [
        (State.Learning, 0, None, None, None),
        (State.Learning, 0, .212, 6.4, 0),
        (State.Learning, 1, 2.3065, 5.1, 10/1440),
        (State.Learning, 1, 2.3065, 5.1, 3),
        (State.Review, None, 15.2, 4.7, 0),
        (State.Review, None, 15.2, 4.7, .99),
        (State.Review, None, 15.2, 4.7, 1),
        (State.Review, None, 15.2, 4.7, 15),
        (State.Review, None, 15.2, 4.7, 90),
        (State.Relearning, 0, 1.8, 7.2, 10/1440),
        (State.Relearning, 0, 1.8, 7.2, 5),
    ]:
        previous = Card(card_id=1,state=state,step=step,stability=stability,
                        difficulty=difficulty,due=base,
                        last_review=None if elapsed is None else base-timedelta(days=elapsed))
        for rating in Rating:
            next_card, _ = scheduler.review_card(previous,rating,base)
            rows.append(dict(retention=retention,now=stamp(base),rating=rating.value,
                             previous=schedule(previous),next=schedule(next_card),
                             retrievability=scheduler.get_card_retrievability(previous,base)))
path=Path(__file__).resolve().parents[1]/"core/src/test/resources/fsrs-6.3.2-oracle.json"
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding="utf-8")
print(f"Generated {len(rows)} transitions into {path}")
