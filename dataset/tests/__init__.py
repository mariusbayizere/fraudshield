"""Makes the dataset test suite a package so its shared helpers can be imported by name.

Required by E15's guard: the comparison logic lives in ``shared_state_guard`` so that it can be
tested directly, and a relative import of it is only legitimate from inside a package. pytest's
``--import-mode=importlib`` handles packaged test directories natively.
"""
