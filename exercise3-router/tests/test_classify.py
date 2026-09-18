"""
TDD: written before exercise3_router.router_app.classify existed.

classify() is the only piece of business logic this exercise owns -
everything else (embedding, index creation, KNN search) is RedisVL and
Redis itself, which this project does not re-test (see
exercise2-rest's tests for the same "don't test the library" principle
applied to Jedis/the MicroProfile REST Client).

FakeRouter below stands in for redisvl.extensions.router.SemanticRouter:
classify() only calls it (router(query) -> object with a .name
attribute), so a plain fake avoids a live Redis connection and an
embedding model in this test.
"""

from types import SimpleNamespace

from exercise3_router.router_app import NO_MATCH, classify


class FakeRouter:
    def __init__(self, match):
        self._match = match
        self.last_query = None

    def __call__(self, query):
        self.last_query = query
        return self._match


def test_classify_returns_the_matched_route_name():
    router = FakeRouter(SimpleNamespace(name="Classical music", distance=0.2))

    result = classify(router, "Tell me about Beethoven's symphonies")

    assert result == "Classical music"
    assert router.last_query == "Tell me about Beethoven's symphonies"


def test_classify_returns_no_match_when_route_name_is_none():
    # This is exactly what SemanticRouter.__call__ returns when no route's
    # distance_threshold is met: a RouteMatch with name=None and
    # distance=None (see redisvl.extensions.router.semantic._classify_route),
    # never None itself.
    router = FakeRouter(SimpleNamespace(name=None, distance=None))

    result = classify(router, "What's the weather like today?")

    assert result == NO_MATCH
