"""
Route definitions for the Exercise 3 semantic router.

Each Route pairs a topic name with a handful of example queries
("references"). RedisVL embeds every reference with the configured
vectorizer and stores the vectors in a Redis index (see router_app.py);
at query time, the input is embedded the same way and matched against
these references by cosine distance - the closest reference's route
wins, provided it falls within that route's distance_threshold.

References were written as short, natural questions/statements a user
might actually type, deliberately varied in phrasing (a direct
question, a recommendation request, a mention of specific well-known
names/works) so each route is represented by more than one way of
expressing the same topic. This gives the embedding model several
points per topic to match against, rather than a single sentence,
which is a fairly narrow target in embedding space.

GENAI_PROGRAMMING originally only had "how do I build/use this"
phrasing (prompting, fine-tuning, RAG, agents) and was missing the
"mentions well-known names/products" style already used by the other
two routes (Star Wars/Dune, Beethoven/Mozart). Measured directly with
the embedding model (sentence-transformers/all-MiniLM-L6-v2, cosine
distance), a query like "is ChatGPT better than Claude?" landed at
distance ~0.80 from every original reference - comfortably outside
distance_threshold=0.5, hence "no matching route" even though the
topic clearly is GenAI. Adding four references naming specific
products (ChatGPT, Claude, Copilot, GPT-4, Gemini) closed that gap to
~0.13-0.27 for that style of query, without pulling any
Science-fiction/Classical-music query across (verified the same way).
The lesson generalizes: recall issues with a semantic router are
usually a reference *coverage* problem for a specific phrasing style,
not something fixed by lowering the threshold globally (which would
also let genuinely unrelated queries through).

distance_threshold=0.5 is used for all three routes: RedisVL's own
examples for a handful of clearly distinct topics use thresholds in
the 0.5-0.72 range (COSINE distance, 0-2, lower is stricter). Since
these three topics (AI/programming, sci-fi entertainment, classical
music) are semantically far apart from each other, a mid-range,
moderately strict value keeps genuine matches close to their topic
while still rejecting clearly unrelated input (see the "no matching
route" demo query in main.py).
"""

from redisvl.extensions.router import Route

GENAI_PROGRAMMING = Route(
    name="GenAI programming topics",
    references=[
        "How do I write a good prompt for a large language model?",
        "What's the best way to fine-tune an LLM on my own data?",
        "Explain how vector embeddings work in a RAG pipeline.",
        "I'm building an AI agent with tool calling, any tips?",
        "What's the difference between few-shot prompting and fine-tuning?",
        "How does semantic search with Redis vector search work?",
        "Is ChatGPT better than Claude for coding tasks?",
        "What's the best AI coding assistant right now?",
        "How does GitHub Copilot compare to other AI tools?",
        "Which large language model is best, GPT-4 or Gemini?",
    ],
    distance_threshold=0.5,
)

SCIFI_ENTERTAINMENT = Route(
    name="Science fiction entertainment",
    references=[
        "What's the best sci-fi movie to watch this weekend?",
        "Tell me about the latest Star Wars or Star Trek series.",
        "I loved the world-building in Dune, any similar books?",
        "Who are the best actors in science fiction films?",
        "What's your favorite time-travel plot in a TV show?",
        "Recommend a good cyberpunk or space opera novel.",
    ],
    distance_threshold=0.5,
)

CLASSICAL_MUSIC = Route(
    name="Classical music",
    references=[
        "What's a good Beethoven symphony for beginners?",
        "Can you recommend a Mozart piano concerto?",
        "I'm looking for a recording of Bach's cello suites.",
        "Who is considered the greatest composer of the Romantic era?",
        "Which orchestra has the best interpretation of Tchaikovsky?",
        "What's the difference between a symphony and a concerto?",
    ],
    distance_threshold=0.5,
)

ALL_ROUTES = [GENAI_PROGRAMMING, SCIFI_ENTERTAINMENT, CLASSICAL_MUSIC]
