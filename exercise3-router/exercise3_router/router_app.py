"""
Builds the SemanticRouter used by this exercise and exposes a small
classify() wrapper around it.

RedisVL's SemanticRouter (see routes.py for the three Route
definitions) does essentially all of the work: embedding references
and queries, creating and querying the underlying Redis vector index,
and picking the closest route within its distance_threshold. classify()
is the only piece of this module's own logic, and exists purely so
main.py's output ("print the route name, or a clear no-match message")
is unit-testable without a live Redis connection or embedding model -
see tests/test_classify.py, which passes in a fake standing in for the
real SemanticRouter. This mirrors how exercise1-sync/exercise2-rest keep
their own logic testable behind a narrow interface to the real client,
rather than mocking a type this project does not own.
"""

import os

from redisvl.extensions.router import SemanticRouter
from redisvl.utils.vectorize import HFTextVectorizer

from exercise3_router.routes import ALL_ROUTES

ROUTER_NAME = "exercise3-topic-router"

# A smaller, faster-to-download alternative to RedisVL's own default
# (sentence-transformers/all-mpnet-base-v2, ~420MB): its retrieval
# quality is more than sufficient for three topics as semantically
# distinct as these - see README.md for the full reasoning.
EMBEDDING_MODEL = "sentence-transformers/all-MiniLM-L6-v2"

NO_MATCH = "no matching route"


def build_router(redis_url: str | None = None, overwrite: bool = False) -> SemanticRouter:
    """
    Creates (or reconnects to) the SemanticRouter backing this exercise.

    redis_url defaults to the REDIS_URL environment variable, matching
    how exercise1-sync/exercise2-rest read their Redis/cluster connection
    details from the environment rather than hard-coding them - see this
    project's README.md for the database this must point to and how it
    was created.

    overwrite controls whether an existing index under this router's
    name is replaced; it is False by default so re-running this program
    does not recreate (and briefly leave empty) the index every time -
    see README.md's "Re-running the program" note.
    """
    redis_url = redis_url or os.environ["REDIS_URL"]
    vectorizer = HFTextVectorizer(model=EMBEDDING_MODEL)
    return SemanticRouter(
        name=ROUTER_NAME,
        routes=ALL_ROUTES,
        vectorizer=vectorizer,
        redis_url=redis_url,
        overwrite=overwrite,
    )


def classify(router, query: str) -> str:
    """
    Returns the name of the best-matching route for query, or NO_MATCH
    if none of the routes' distance thresholds were met.

    router only needs to be callable as router(query), returning an
    object with a .name attribute (str | None) - exactly
    SemanticRouter.__call__'s contract (it always returns a RouteMatch,
    using name=None to mean "no route was close enough", rather than
    returning None itself - see redisvl.extensions.router.semantic
    .SemanticRouter._classify_route).
    """
    match = router(query)
    if match.name is None:
        return NO_MATCH
    return match.name
