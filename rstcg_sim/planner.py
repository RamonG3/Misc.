"""A smarter bot for balance testing: it plays each close decision out to the end.

When the greedy bot's top choices are close, the RolloutBot takes the few best
of them and, for each, plays the rest of the game out many times on a copy
(both sides played by the greedy bot), then picks the move that won most often.
Each copy resamples hidden information from its own point of view (its deck
order, the opponent's hand), so it can't peek, and every candidate is tried on
the same samples so the comparison is fair.

That's what catches delayed payoffs the greedy bot can't see (a quest finishing
three turns from now, a prayer that needs one more Bones, saving energy for a
special): the playouts actually get there. It has no tuned weights of its own,
so there's nothing to nudge toward a result we'd like. It is still built on the
greedy bot, so it's a better player, not a perfect one.
"""
import random

from bot import HeuristicBot


class RolloutBot(HeuristicBot):
    def __init__(self, seed=None, width=4, rollouts=12, margin=4.0, horizon=80):
        super().__init__(seed)
        self.width = width
        self.rollouts = rollouts
        self.margin = margin      # skip the search when greedy's best leads by more than this
        self.horizon = horizon    # turns a playout may run before it counts as a draw
        self.searches = 0
        self.overrides = 0        # searches that picked something other than greedy's top choice

    def setup(self, game):
        super().setup(game)
        self.sim = [HeuristicBot(), HeuristicBot()]
        for b in self.sim:
            b.setup(game)

    def choose(self, g, i, legal):
        scored = sorted(((self.score(g, i, a) + self.rng.random() * self.noise, a) for a in legal),
                        key=lambda x: x[0], reverse=True)
        if len(scored) == 1 or scored[0][0] >= 900 or scored[0][0] - scored[1][0] > self.margin:
            return scored[0][1]
        cands = [a for _, a in scored[: self.width]]
        wins = dict.fromkeys(cands, 0.0)
        for _ in range(self.rollouts):
            seed = self.rng.random()
            for a in cands:
                wins[a] += self.playout(g, i, a, seed)
        self.searches += 1
        best = max(cands, key=lambda a: wins[a])      # ties go to greedy's order
        self.overrides += best != cands[0]
        return best

    def playout(self, g, i, a, seed):
        s = g.clone(view=i, rng=random.Random(seed))
        s.agents = self.sim
        for k, b in enumerate(self.sim):
            b.rng = random.Random(seed + k)
        actions, free = s.spend(i, a, g.actions_left, g.free_plays_used)
        s.apply(i, a)
        if s.winner is None:
            s.resume_turn(actions, free)
        end = s.turn + self.horizon
        while s.winner is None and s.turn < end:
            s.play_turn()
        if s.winner is None:
            return 0.5
        return 1.0 if s.winner == i else 0.0
