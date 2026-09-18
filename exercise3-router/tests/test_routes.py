"""
Verifies the route definitions themselves match the exercise's spec
(exactly three routes, with these exact names), independently of
routing behavior (covered by test_classify.py) or Redis (never
touched by this module - see routes.py).
"""

from exercise3_router.routes import ALL_ROUTES


def test_defines_exactly_the_three_routes_required_by_the_exercise():
    names = {route.name for route in ALL_ROUTES}

    assert names == {
        "GenAI programming topics",
        "Science fiction entertainment",
        "Classical music",
    }


def test_every_route_has_multiple_references_and_a_distance_threshold():
    for route in ALL_ROUTES:
        assert len(route.references) >= 3, f"{route.name} needs more than one reference"
        assert 0 < route.distance_threshold <= 2
