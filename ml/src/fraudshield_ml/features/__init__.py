"""Feature computation: the declarative registry, and the two independent paths.

``registry`` is declarative only and computes nothing. ``batch`` and ``online`` are independent
implementations that must not import each other — see ``docs/ml/training_serving_parity.md``
Decision 4, which a test enforces by walking the import graph.
"""
