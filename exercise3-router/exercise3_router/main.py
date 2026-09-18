"""
Entry point for Exercise 3.

Builds the semantic router against the Redis database configured via
REDIS_URL (see README.md), then classifies either the queries given as
command-line arguments, or - if none are given - a small built-in demo
set covering all three routes plus one deliberately unrelated query.

Per the exercise ("the route's output only needs to show the name of
the route"), each query prints only the matched route name (or
NO_MATCH), one per line, in the same order as the input queries.
"""

import sys

from exercise3_router.router_app import build_router, classify

DEMO_QUERIES = [
    "How do I fine-tune a language model on my own dataset?",
    "What's the best sci-fi show to binge this weekend?",
    "Can you recommend a good recording of Beethoven's 9th symphony?",
    "What's the weather going to be like tomorrow?",
]


def main(argv: list[str]) -> int:
    router = build_router()

    queries = argv[1:] or DEMO_QUERIES
    for query in queries:
        print(classify(router, query))

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
