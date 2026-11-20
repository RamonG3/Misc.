/* Story Mode: the chapters as data, and the runner that walks through them (STORY_BRIEF.md).
 *
 *   const S = RSTCGStory.make()
 *   S.CHAPTERS, S.OPP, S.SPEAKERS
 *   S.start(chId)            a new run of a chapter: { ch, node, i, r, dots, at, tone, road, done }
 *   S.step(run)              what the run is on now: a step object (see below), { onMap: true } on the map,
 *                            or { finished: true } once the chapter is over
 *   S.advance(run, pick)     past the current step (pick: the option's index, for a choice)
 *   S.enter(run, dotId)      from the map into an open dot
 *   S.dotsOf(run)            the chapter's dots, each with state 'open' | 'done' (dots not reached aren't listed)
 *
 * A run is plain JSON (it is saved in the profile as it is), and only ever points into the script by
 * node id and index, so a script edit can't leave a save holding stale text.
 *
 * Steps:
 *   { who, t }              a line: who is a speaker id (SPEAKERS), 'you', or absent for narration
 *   { choose: [opt], key }  the player picks one. opt: { t, then: [steps], tone, road, go }
 *                           tone: recorded as run.tone[key]; road: run.road; go: jump to that node afterwards
 *   { scene, night }        the place changes (no stop). Also: art (a painting other than the place's own),
 *                           music (a track other than the place's own: the page's PLACE_MUSIC)
 *   { who, t, bg }          a line that also changes the painting (the place stays the same)
 *   { flash: 'red' }        a flash of light (no stop)
 *   { map: dotId }          open that dot, close the one you came from, and go to the map
 *   { duel: oppId }         a duel; won: on to the next step; lost: the opponent's lose line, then retry or map
 *   { reward: card, from }  a reward card is handed over
 *   { end: road }           the chapter is complete by that road
 *
 * No randomness and nothing from the page here: node tests it (web/test/story_test.js).
 */
(function (root) {
  'use strict';

  // ---------- who speaks ----------
  // face: the chathead's picture key in the page (AS.face); until a picture exists the page draws a medallion.
  const SPEAKERS = {
    hans: { name: 'Hans', face: 'Hans' },
    stranger: { name: '???', face: 'Stranger' },
    bandit: { name: 'Bandit', face: 'Bandit' },
    highwayman: { name: 'Highwayman', face: 'Highwayman' },
    wom: { name: 'Wise Old Man', face: 'Wise Old Man' },
    skeleton: { name: 'Skeleton', face: 'Skeleton' },
    delrith: { name: 'Delrith', face: 'Delrith' },
    voice: { name: '???', face: null },
    goblin: { name: 'Goblin', face: 'Goblin' },
    reldo: { name: 'Reldo', face: 'Reldo' },
    wizard: { name: 'Dark Wizard', face: 'Dark Wizard' },
    draynor: { name: 'Count Draynor', face: 'Count Draynor' },
    // chapter 2
    knight: { name: 'White Knight', face: 'White Knight' },
    amik: { name: 'Sir Amik Varze', face: 'Sir Amik Varze' },
    cultist: { name: 'Zamorak Cultist', face: 'Zamorak Cultist' },
    zknight: { name: 'Zombie Knight', face: 'Zombie Knight' },
    corpse: { name: 'Crawling Corpse', face: 'Crawling Corpse' },
    dragith: { name: 'Dragith Nurn', face: 'Dragith Nurn' },
    scout: { name: 'Kinshra Scout', face: 'Kinshra Scout' },
    shadow: { name: '???', face: 'Shadow' },
    melzarv: { name: '???', face: null },
    melzar: { name: 'Melzar the Mad', face: 'Melzar the Mad' },
  };

  // ---------- the opponents ----------
  // deck: the card list (cards.json names). level: the bot level. capes: the capes it may bring (one at random).
  // cry: what it says as the duel starts (the last line before the Duel button).
  // lose: what it says when you lose, before Retry. lines: what it says during the duel ("start", "firstHit").
  // location: a Location that starts in play and can't be replaced (STORY_BRIEF.md, rules change 1).
  // deckName: the opponent's deck in cards.json ("Story decks", from STORY_DECKS in cards.py, where the
  // simulator tunes them: STORY_OPPONENTS.md).
  const OPP = {
    bandit: { name: 'Bandit', speaker: 'bandit', deckName: 'Bandit', level: 'normal',
      cry: { who: 'bandit', t: "Let's see how much you're worth!" },
      lose: { who: 'bandit', t: "Come back when you've grown a spine." } },
    highwayman: { name: 'Highwayman', speaker: 'highwayman', deckName: 'Highwayman', level: 'easy',
      cry: { who: 'highwayman', t: "I'll take that purse off your corpse, then!" },
      lose: { who: 'highwayman', t: 'A pleasure doing business.' } },
    goblins: { name: 'Goblin Band', speaker: 'goblin', deckName: 'Goblin Band', level: 'normal',
      cry: { who: 'goblin', t: 'Smash it! SMASH IT!' },
      lose: { who: 'goblin', t: 'We win! We never win!' } },
    skeleton: { name: 'Skeleton', speaker: 'skeleton', deckName: 'Skeleton', level: 'normal',
      cry: { who: 'skeleton', t: 'The skeleton lurches out of the dark, its rusty blade raised high.', act: true },
      lose: { t: 'The skeleton clatters back into the shadows, waiting.' } },
    wizard: { name: 'Dark Wizard', speaker: 'wizard', deckName: 'Dark Wizard', level: 'hard',
      cry: { who: 'wizard', t: 'Your bones will feed the circle!' },
      lose: { who: 'wizard', t: 'Run, little bookworm. Run while you can.' } },
    delrith: { name: 'Delrith', speaker: 'delrith', deckName: 'Delrith', level: 'hard', boss: true, location: 'Draynor Manor',
      cry: { who: 'delrith', t: 'Kneel, and I might make it quick!' },
      lines: {
        start: [{ who: 'delrith', t: 'No Silverlight? Then you are already dead.' }],
        firstHit: [{ who: 'delrith', t: '...I felt that.' }, { who: 'delrith', t: 'Without Silverlight, nothing can harm me! ...Can it?' }],
      },
      lose: { who: 'delrith', t: 'Run along. Come back with a sword, if you can find one.' } },
    draynor: { name: 'Count Draynor', speaker: 'draynor', deckName: 'Count Draynor', level: 'hard', boss: true, location: 'Moonlit Circle',
      cry: { who: 'draynor', t: 'Come closer. Let me taste your courage.' },
      lines: {
        start: [{ who: 'draynor', t: 'No stake? No garlic? Then you have no hope at all.' }],
        firstHit: [{ who: 'draynor', t: 'You... wounded me? That should not be possible.' }],
      },
      lose: { who: 'draynor', t: 'Run home, little one. I will find you later.' } },
    // ---------- chapter 2 (STORY_CH2_SCRIPT.md) ----------
    cultist: { name: 'Zamorak Cultist', speaker: 'cultist', deckName: 'Zamorak Cultist', level: 'hard',
      cry: { who: 'cultist', t: 'Zamorak take you!' },
      lose: { who: 'cultist', t: 'Tell the Knights to send someone tougher.' } },
    zknight: { name: 'Zombie Knight', speaker: 'zknight', deckName: 'Zombie Knight', level: 'easy',
      cry: { who: 'zknight', t: '...For... Falador...' },
      lose: { t: 'The knight shambles back to its post, as if nothing happened.' } },
    corpse: { name: 'Crawling Corpse', speaker: 'corpse', deckName: 'Crawling Corpse', level: 'hard',
      cry: { who: 'corpse', t: 'The corpse lunges.', act: true },
      lose: { t: 'The corpse drags itself back into the dark.' } },
    dragith: { name: 'Dragith Nurn', speaker: 'dragith', deckName: 'Dragith Nurn', level: 'hard', boss: true, location: 'Falador Tunnels',
      cry: { who: 'dragith', t: 'Rise, and serve!' },
      lines: {
        start: [{ who: 'dragith', t: 'Your bones will carry my books for a thousand years.' }],
        firstHit: [{ who: 'dragith', t: 'Hm. You have more fight in you than most corpses.' }],
      },
      lose: { who: 'dragith', t: "Rest now. I'll call you when I need you." } },
    scout: { name: 'Kinshra Scout', speaker: 'scout', deckName: 'Kinshra Scout', level: 'normal',
      cry: { who: 'scout', t: "Then you'll be dead travellers. For the Kinshra!" },
      lose: { who: 'scout', t: "Turn back. Next time, I won't miss." } },
    shadow: { name: 'Shadow', speaker: 'shadow', deckName: 'Shadow', level: 'normal',
      cry: { who: 'shadow', t: 'It draws another blade.', act: true },
      lose: { t: 'The shadow sinks back into the corner, and waits.' } },
    melzar: { name: 'Melzar the Mad', speaker: 'melzar', deckName: 'Melzar the Mad', level: 'hard', boss: true, location: 'Hidden Prison',
      cry: { who: 'melzar', t: 'Out of my way! The cabbages are waiting!' },
      lines: {
        start: [{ who: 'melzar', t: "Swing it, throw it, cast it. It's all cabbage to me!" }],
        firstHit: [{ who: 'melzar', t: 'Ow! That was my good arm. Or my good cabbage. One of them.' }],
      },
      lose: { who: 'melzar', t: 'Free! Free as a cabbage in a field!' } },
  };

  // ---------- chapter 1 (STORY_CH1_SCRIPT.md) ----------
  const say = (who, t) => ({ who, t });
  const you = (t) => ({ who: 'you', t });
  const nar = (t) => ({ t });
  const opt = (t, then = [], extra = {}) => Object.assign({ t, then }, extra);

  const CH1 = {
    id: 'ch1', n: 1, title: 'A Flash of Red',
    region: 'Misthalin',
    start: 'intro',
    home: 'lumbridge',
    roadText: { A: 'the road to Draynor', B: 'the road to Varrock' },
    doneMsg: 'Chapter complete. Falador awaits, in chapter 2.',
    // where things are on the map (its 1600 x 1000 picture)
    dots: {
      lumbridge: { label: 'Lumbridge', x: 1000, y: 640, node: null, town: true },
      swamp: { label: 'Lumbridge Swamp', x: 930, y: 850, node: 'swamp' },
      a_road: { label: 'The road to Draynor', x: 800, y: 650, node: 'a_road', road: 'A' },
      a_manor: { label: 'Draynor Manor', x: 610, y: 470, node: 'a_manor', road: 'A' },
      a_cellar: { label: 'Draynor Manor: the cellar', x: 650, y: 455, node: 'a_cellar', road: 'A', boss: true },
      b_road: { label: 'The road to Varrock', x: 1040, y: 450, node: 'b_road', road: 'B' },
      b_circle: { label: 'The stone circle', x: 1060, y: 300, node: 'b_circle', road: 'B', boss: true },
    },
    places: [                               // names on the map that aren't dots
      { label: 'Varrock', x: 1110, y: 200, town: true },
      { label: 'Draynor Village', x: 590, y: 640, town: true },
      { label: 'Falador', x: 240, y: 330, town: true, soon: true },
    ],
    rewards: { A: 'Claws of Guthix', B: 'Shield of Arrav' },
    bosses: { A: 'delrith', B: 'draynor' },  // each road's boss: a rematch opens once you've finished that road
    nodes: {
      intro: [
        { scene: 'Lumbridge' },
        say('hans', "Hello there! You're new around here, aren't you? I can always tell."),
        say('hans', "I'm Hans. I walk around the castle. All day, every day. Have done for years."),
        { choose: [
          opt("Years? Doesn't that get boring?", [say('hans', 'Never. Every lap, I notice something new. Today it was a pebble.')]),
          opt("I'm here to become an adventurer.", [say('hans', "Then you've come to the right place. Every great adventurer starts in Lumbridge. Most of them start by getting lost.")]),
          opt('What is there to do around here?', [say('hans', "Funny you should ask. I've got just the job for a fresh face.")]),
        ] },
        say('hans', "A bandit has been robbing folk at the edge of Lumbridge Swamp. The Duke's guards are... busy."),
        say('hans', "Deal with him, and there's a reward in it for you. Probably."),
        { choose: [
          opt("I'll handle it.", [say('hans', "That's the spirit.")]),
          opt("Why can't the guards do it?", [say('hans', "They're guarding. It's in the name.")]),
          opt('Probably?', [say('hans', 'Definitely probably.')]),
        ] },
        say('hans', 'Head south, past the church. Mind the bog.'),
        { map: 'swamp' },
      ],
      swamp: [
        { scene: 'Lumbridge Swamp' },
        say('bandit', 'Well, well. Another tourist. Hand over your coins and nobody gets hurt.'),
        { choose: [opt('Not today.'), opt('How about I hand you a beating instead?'), opt("I only have one coin, and it's sentimental.")] },
        say('bandit', 'Have it your way.'),
        { duel: 'bandit' },
        say('bandit', "Alright, alright! I'm going! You'll regret this."),
        // The stranger (2026-11-20: Ramon's painting, and his music: the swamp's music stops, then Ambient Room).
        // Still in the swamp, before Lumbridge. Steps were changed, never added or removed, so saves keep their place.
        { scene: 'Lumbridge Swamp', art: 'Lumbridge Swamp stranger', music: 'Ambient Room' },
        nar('As you turn to leave the swamp, someone is standing in your way.'),
        nar("A hooded figure in dark robes. You can't see a face."),
        say('stranger', '......'),
        { key: 'stranger1', choose: [
          opt('Excuse me. Could I get past?', [], { tone: 'polite' }),
          opt('Who are you?', [], { tone: 'curious' }),
          opt("Move, or I'll move you.", [], { tone: 'threatening' }),
          opt("Nice robe. Bit warm for the season, isn't it?", [], { tone: 'witty' }),
        ] },
        say('stranger', '......'),
        { key: 'stranger2', choose: [
          opt('Have I done something to offend you?', [], { tone: 'polite' }),
          opt('What do you want with me?', [], { tone: 'curious' }),
          opt("You're beginning to look like free experience. Move. Now.", [], { tone: 'threatening' }),
          opt("Is this a staring contest? Because I think I'm winning.", [], { tone: 'witty' }),
        ] },
        say('stranger', '......'),
        { t: 'The figure draws a sword. Its blade is a deep, impossible red, as if it were cut from a single ruby.', bg: 'Lumbridge Swamp stranger close' },
        nar('The sword rises.'),
        { flash: 'red' },
        { scene: 'Lumbridge Swamp', art: 'Lumbridge Swamp stranger gone', empty: true, music: 'Ambient Room' },   // swapped under the flash
        nar('When your sight comes back, the path is empty.'),
        you('...What was that?'),
        { scene: 'Lumbridge' },
        say('hans', 'Back already! And the bandit?'),
        you("He won't be robbing anyone for a while."),
        say('hans', 'Splendid! Lumbridge thanks you.'),
        say('hans', "You look pale. Paler than new adventurers usually look, anyway. What's wrong?"),
        { choose: [
          opt("I'm fine.", [say('hans', "You're a terrible liar. Out with it.")]),
          opt('Someone stopped me on the road.'),
        ] },
        you('There was someone on the road. Hooded, in dark robes. They never said a word.'),
        you('They drew a sword made of ruby. There was a flash of red light, and they were gone.'),
        say('hans', 'A ruby sword?'),
        say('hans', "I've walked around this castle a long time. I've never heard of anything like it."),
        say('hans', 'But I know two people who might.'),
        say('hans', 'The Wise Old Man lives in Draynor Village, west of here. He knows a bit about everything. Mostly trouble.'),
        say('hans', "Or there's Reldo, the librarian at Varrock Palace, to the north. If it's written down anywhere, he's read it."),
        { fork: true, choose: [
          opt("I'll find the Wise Old Man in Draynor.", [], { road: 'A', go: 'toA' }),
          opt("I'll find Reldo in Varrock.", [], { road: 'B', go: 'toB' }),
        ] },
      ],
      toA: [say('hans', "Good luck. And whatever that was... don't go looking for it alone."), { map: 'a_road' }],
      toB: [say('hans', "Good luck. And whatever that was... don't go looking for it alone."), { map: 'b_road' }],

      // ----- Road A: Draynor -----
      a_road: [
        { scene: 'The road to Draynor' },
        say('highwayman', 'Stand and deliver!'),
        { choose: [opt('Deliver what?'), opt("You're the reason I keep my coins in the bank."), opt('No.')] },
        say('highwayman', 'Your money or your life!'),
        { duel: 'highwayman' },
        say('highwayman', 'Curses! Another day, another empty purse.'),
        { scene: 'Draynor Village' },
        nar('Draynor Village. Quiet, apart from the market.'),
        nar('An old man with a long white beard is stuffing gear into a sack.'),
        you('Excuse me. Are you the Wise Old Man?'),
        say('wom', "Depends who's asking, and whether they owe me money."),
        { choose: [
          opt('I need to ask you about a ruby sword.', [say('wom', 'A what? Never mind. Not now.')]),
          opt("You're older than I expected.", [say('wom', "And you're shorter. We all have our burdens.")]),
        ] },
        say('wom', "I'm sorry, but I have no time. I'm on my way to Draynor Manor."),
        say('wom', "Years ago, an adventurer drove a stake through Count Draynor's heart in the cellar of that manor. The village has slept easy ever since."),
        say('wom', 'Then, two days ago, Ernest came running out of the manor, screaming. Something about a horrible monster.'),
        { choose: [
          opt('Ernest? The one who was turned into a chicken?', [say('wom', "The very same. He's had a difficult few years.")]),
          opt('Maybe he just saw a spider.', [say('wom', 'Ernest has been a chicken. He does not scare easily.')]),
        ] },
        say('wom', 'I fear an ancient darkness has woken in that house.'),
        say('wom', 'Come with me. The sooner this is dealt with, the sooner we can talk about your sword.'),
        { choose: [
          opt('Lead the way.'),
          opt('Do I have a choice?', [say('wom', "Of course. But I'm old and you're curious. We both know how this ends.")]),
        ] },
        { map: 'a_manor' },
      ],
      a_manor: [
        { scene: 'Draynor Manor' },
        nar('The great doors swing shut behind you with a BOOM.'),
        you('Gah!'),
        say('wom', 'Ha! You get used to that. I once used those doors to chop a particularly stubborn log in half.'),
        nar('Something rattles in the dark.'),
        { duel: 'skeleton' },
        { scene: 'Draynor Manor stairs' },
        nar('The skeleton collapses into a heap of dusty bones.'),
        you('There. Your ancient evil is slain. Very ancient, by the look of the dust.'),
        you('Can we talk now?'),
        say('wom', 'That was not the evil I meant.'),
        say('wom', "But it's not a good sign, either. Skeletons don't get up on their own."),
        nar('You search the manor room by room. At last you find the stairs down to the cellar.'),
        nar('The Wise Old Man takes a wooden stake from his sack.'),
        you('Is that... a stake?'),
        say('wom', 'Just in case.'),
        { choose: [
          opt('You think Count Draynor is back.', [say('wom', "I hope I'm wrong. I usually am not.")]),
          opt('Should I be worried?', [say('wom', 'Yes.')]),
        ] },
        { map: 'a_cellar' },
      ],
      a_cellar: [
        { scene: 'Draynor Manor cellar' },
        nar('The cellar smells of damp earth and something older.'),
        nar("Count Draynor's coffin lies open. On the floor, where the Count fell, is a small pile of ash. A cracked, dusty monocle sits on top of it."),
        say('wom', 'Still dead. Oh, thank Guthix.'),
        say('voice', 'I would not celebrate just yet.'),
        nar('Something climbs out of the coffin. It is not the Count.'),
        say('wom', 'Delrith?! But you were destroyed! The whole of Varrock saw it!'),
        say('delrith', 'And yet, here I stand.'),
        nar("Delrith raises a hand. A blast of fire throws the Wise Old Man against the wall. He doesn't get up."),
        say('delrith', 'Now. You.'),
        say('delrith', 'Where is your Silverlight, little adventurer?'),
        { duel: 'delrith' },
        say('delrith', 'No... not again...'),
        nar("Delrith crumbles into ash, beside the Count's."),
        nar('Behind you, someone groans.'),
        say('wom', 'Ugh. My head. Did I miss anything?'),
        you('Delrith. I beat him.'),
        say('wom', "Delrith. Climbing out of Draynor's coffin. That makes no sense at all."),
        say('wom', 'Why come back now? And why here, of all places?'),
        you('It might have something to do with the stranger I met. The one with the ruby sword.'),
        say('wom', "The... well why didn't you mention that?!"),
        you('*sigh*'),
        say('wom', "Never mind. We're going to Falador. I have an associate there who knows more about this sort of thing than I'd like."),
        say('wom', "And take this. You've earned it."),
        { reward: 'Claws of Guthix', from: 'wom' },
        say('wom', "Guthix keeps the balance. Something has tipped it. Let's find out what."),
        { end: 'A' },
      ],

      // ----- Road B: Varrock -----
      b_road: [
        { scene: 'The road to Varrock' },
        say('goblin', 'Human! Get it!'),
        say('goblin', 'You get it!'),
        say('goblin', 'We ALL get it!'),
        { duel: 'goblins' },
        say('goblin', 'Run away! Run away!'),
        { scene: 'Varrock Palace library' },
        nar('Varrock Palace library. Shelves to the ceiling, and one very busy man.'),
        you('Reldo?'),
        say('reldo', 'Hm? Yes. One moment. Demons, demons... binding demons... no, no...'),
        you('I need to ask you about...'),
        say('reldo', "Sorry, sorry. I can't talk. I have a demon to defeat. Well. Re-defeat."),
        { choose: [opt('Re-defeat?'), opt('A demon? In Varrock?')] },
        say('reldo', 'Years ago, a demon called Delrith was summoned at the stone circle south of the city.'),
        say('reldo', 'An adventurer banished him with Silverlight, the demon-slaying sword.'),
        say('reldo', 'And then we let the adventurer keep it. Brilliant. Absolutely brilliant.'),
        say('reldo', 'Now there are robed figures at the stone circle again. Dark wizards, chanting.'),
        say('reldo', "So I'm looking for a way to stop Delrith without Silverlight. Just in case."),
        { choose: [
          opt("Maybe they're just having a picnic.", [say('reldo', 'In black robes. At night. Chanting. Yes, that must be it.')]),
          opt('Why not just ask them?', [say('reldo', "...That's actually not a bad idea.")]),
        ] },
        say('reldo', "Perhaps I should find out what they're really doing. Perhaps they're harmless."),
        say('reldo', "Would you come with me? Just in case they're not."),
        { map: 'b_circle' },
      ],
      b_circle: [
        { scene: 'The stone circle' },
        nar('As you reach the stone circle, the sky darkens. Too early. Much too early.'),
        { scene: 'The stone circle', night: true },
        say('reldo', "That's... not normal. That is not normal at all."),
        say('wizard', 'You should not have come here, librarian.'),
        say('reldo', 'See? Perfectly friendly.'),
        { duel: 'wizard' },
        nar('The wizard cries out, and crumbles into a pile of ash.'),
        say('reldo', "Well done! Though... people don't usually do that. The ash, I mean."),
        say('reldo', 'Still. At least they failed to summon some sort of catastrophic, world-ending monster.'),
        say('voice', 'For a librarian, you are not very observant.'),
        nar('Something rises from the middle of the stone circle. Pale. Tall. Dressed for a funeral that ended long ago.'),
        say('reldo', 'C-C-Count D-Draynor?!'),
        say('draynor', 'Good evening.'),
        { duel: 'draynor' },
        say('draynor', 'This... is not... over...'),
        nar('The Count crumbles to ash. Slowly, the sky clears.'),
        { scene: 'The stone circle' },
        say('reldo', "That's impossible. Count Draynor was slain years ago. Staked, in his own cellar."),
        you('It might have something to do with the stranger I met. The one with the ruby sword.'),
        say('reldo', 'A ruby sword...'),
        say('reldo', 'That reminds me of something. Old research, on another matter entirely. Obscure. Very obscure.'),
        say('reldo', "Falador. I'm sure it had something to do with Falador. Go there."),
        say('reldo', "And take this. It's been gathering dust in the museum's back room for years."),
        { reward: 'Shield of Arrav', from: 'reldo' },
        say('reldo', "There's a story behind that shield. Remind me to tell you one day."),
        { end: 'B' },
      ],
    },
  };

  // ---------- chapter 2 (STORY_CH2_SCRIPT.md, approved by Ramon 2026-11-20) ----------
  // Starts in Falador whichever road chapter 1 took; one line (the old enemy you tell Sir Amik about) depends on
  // it: a step with `when: { ch1: 'A' }` is played only if the main story's chapter 1 went that way (run.prev).
  const CH2 = {
    id: 'ch2', n: 2, title: 'Falador',
    region: 'Asgarnia',
    start: 'intro',
    home: 'falador',
    roadText: { A: "the White Knights' road", B: "the cultist's road" },
    doneMsg: "Chapter complete. The Black Knights' Fortress lies to the north.",
    dots: {
      falador: { label: 'Falador', x: 760, y: 470, node: null, town: true },
      camp: { label: 'The forest camp', x: 480, y: 760, node: 'camp' },
      a_vaults: { label: 'The castle vaults', x: 700, y: 640, node: 'a_vaults', road: 'A' },
      a_tunnels: { label: 'The tunnels', x: 590, y: 700, node: 'a_tunnels', road: 'A' },
      a_chamber: { label: "Dragith's chamber", x: 660, y: 790, node: 'a_chamber', road: 'A', boss: true },
      b_road: { label: 'The road to Edgeville', x: 1110, y: 420, node: 'b_road', road: 'B' },
      b_house: { label: 'The house at the edge of town', x: 1300, y: 250, node: 'b_house', road: 'B' },
      b_prison: { label: 'The hidden prison', x: 1420, y: 230, node: 'b_prison', road: 'B', boss: true },
    },
    places: [
      { label: 'Edgeville', x: 1440, y: 330, town: true },
      { label: 'Taverley', x: 380, y: 440, town: true },
      { label: 'Port Sarim', x: 1010, y: 860, town: true },
      { label: "Black Knights' Fortress", x: 920, y: 175, town: true, soon: true },
    ],
    rewards: { A: 'White Knight Armor', B: 'Cabbage Armor' },
    bosses: { A: 'dragith', B: 'melzar' },
    nodes: {
      intro: [
        { scene: 'Falador' },
        nar('Falador. White walls, white towers, and a great many knights in very clean armour.'),
        { scene: "The White Knights' Castle" },
        you("I'm here to see Sir Amik Varze."),
        say('knight', "Are you, now. Sir Amik doesn't see everyone who wanders up to the gate."),
        { choose: [
          opt("It's important. It's about a stranger with a ruby sword.", [say('knight', "Everyone's business is important. Everyone's stranger is strange.")]),
          opt('A friend sent me. A friend of his, too.', [say('knight', 'Sir Amik has a great many friends. Most of them write ahead.')]),
          opt("I'll wait.", [say('knight', "You'll be waiting a while.")]),
        ] },
        nar('The knight sighs.'),
        say('knight', "Fine. If you want an audience, prove you're worth one."),
        say('knight', "Our scouts have spotted Zamorakians camped in the forest outside the walls. Clear them out, and we'll talk."),
        { choose: [
          opt('Consider it done.', [say('knight', "We'll see.")]),
          opt("Why haven't you cleared them out yourselves?", [say('knight', "We have. Twice. They keep coming back. You're the third attempt.")]),
        ] },
        { map: 'camp' },
      ],
      camp: [
        { scene: 'The forest camp' },
        nar('A ring of tents among the trees. A smouldering fire. Red banners.'),
        say('cultist', 'The White Knights sent one adventurer? They must be getting desperate.'),
        { choose: [opt('The Knights send their regards.'), opt('Pack up and leave, and nobody gets hurt.'), opt('Nice banners. Did you make them yourself?')] },
        { duel: 'cultist' },
        say('cultist', 'Enough! Enough. I yield.'),
        say('cultist', "You fool. You and your Knights both. We're not the real threat to Falador."),
        you('Then what is?'),
        { flash: 'red', far: true },
        nar("Far to the north, a flash of red light fills the sky. Then it's gone."),
        you('...oh no.'),
        say('cultist', "Whatever did that. That's the real threat."),
        say('cultist', "You've seen it before. It's written all over your face."),
        say('cultist', 'I know where to start looking. I could take you there.'),
        say('cultist', "So, adventurer. What's it going to be?"),
        { fork: true, choose: [
          opt("You're coming with me to the White Knights.", [], { road: 'A', go: 'toA' }),
          opt("Lead the way. But I'm watching you.", [], { road: 'B', go: 'toB' }),
        ] },
      ],

      // ----- Road A: the White Knights -----
      toA: [
        say('cultist', "You can't be serious. Did you not see the sky?"),
        you('I saw it. You can tell the Knights all about it.'),
        say('cultist', "...Fine. I've no wish to lose to you twice in one day."),
        { scene: "The White Knights' Castle" },
        say('knight', 'Back already? And you brought a guest.'),
        you("One Zamorakian, as asked. The camp's empty."),
        say('knight', "Well done. Very well done. We'll take him from here."),
        say('cultist', "You'll regret this. Both of you."),
        say('knight', 'They always say that.'),
        say('knight', 'Come. Sir Amik will see you now.'),
        { scene: "Sir Amik's hall" },
        say('amik', "So you're the one who cleared the forest. My knights speak well of you. That's rare. They don't speak well of each other."),
        say('amik', 'What brings you to Falador?'),
        you("There's a stranger. Hooded, dark robes, never says a word. They carry a sword that looks as if it's made of ruby."),
        you("When they raise it, there's a flash of red light. And afterwards, something old comes back."),
        Object.assign(you("Delrith climbed out of Count Draynor's coffin. He was destroyed years ago, in Varrock."), { when: { ch1: 'A' } }),
        Object.assign(you('Count Draynor rose from the stone circle outside Varrock. He was staked years ago, in his own cellar.'), { when: { ch1: 'B' } }),
        you('The wrong enemy, in the wrong place. And just now, there was another flash. North of here.'),
        say('amik', 'Hmm.'),
        say('amik', "I don't know who your stranger is, or why any of this is happening."),
        say('amik', 'But I do know this. A few days ago, someone broke into the lower vaults of this castle.'),
        say('amik', "That's where we keep what we take from the Zamorakians. Things too dangerous to use, and too dangerous to leave lying about."),
        say('amik', 'In all the years the White Knights have guarded that vault, no one has ever got inside. Not once.'),
        say('amik', 'And now, in the same week, your stranger appears, and the dead start walking again.'),
        say('amik', "I don't believe in coincidences. Not that big."),
        say('amik', 'We took an inventory. Only one thing was missing.'),
        say('amik', 'A chest of black steel, sealed with a lock unlike any other. None of us could open it. Not even Sir Tiffy.'),
        { choose: [
          opt('What was in the chest?', [say('amik', 'We never found out. That was rather the point of the lock.')]),
          opt("Who's Sir Tiffy?", [say('amik', 'A man who can open anything. Usually with a hammer.')]),
        ] },
        say('amik', 'Go down to the vaults. Look for anything my knights missed.'),
        say('amik', 'And be careful. Whoever took that chest knew what we keep down there. They may not be the only ones looking for it.'),
        { map: 'a_vaults' },
      ],
      a_vaults: [
        { scene: 'The castle vaults' },
        nar('The vaults beneath the castle. Cold, quiet, and darker than they should be.'),
        nar('A knight in white armour stands in the corridor, with its back to you.'),
        you('Hello? Sir Amik sent me down.'),
        nar('It turns. Its face is grey. Its eyes are empty.'),
        { duel: 'zknight' },
        nar('The knight falls, and its armour clatters on the stone.'),
        you('That was a White Knight. What happened to him?'),
        nar('Inside the vault, shelves and cases line every wall: staffs, idols, red-stained daggers. Everything is thick with dust.'),
        nar('Except one spot: a long, clean shape on the floor, where a chest once sat.'),
        nar('Beside it, the dust is scraped and smeared, as if something had dragged itself to the chest instead of walking.'),
        you('Something crawled in here.'),
        nar('The marks lead back across the room, and stop at a blank stone wall.'),
        { choose: [
          opt('Knock on it.', [nar('It sounds hollow.')]),
          opt('Push it.', [nar('It shifts.')]),
        ] },
        nar('The wall swings open like a door. Behind it, a tunnel slopes down into the dark.'),
        you("A secret wall. Of course there's a secret wall."),
        { map: 'a_tunnels' },
      ],
      a_tunnels: [
        { scene: 'The tunnels' },
        nar('The tunnel is narrow and damp. Ahead, something is scraping along the floor.'),
        nar("It's half a body, pulling itself along by its arms."),
        you('...That explains the marks.'),
        { duel: 'corpse' },
        nar('The corpse stops moving. This time, for good.'),
        { map: 'a_chamber' },
      ],
      a_chamber: [
        { scene: "Dragith's chamber" },
        nar('The tunnel opens into a chamber lit by black candles. Bones are stacked against the walls.'),
        say('voice', 'Where is it? Who would have taken it? Who COULD have taken it?'),
        nar('A robed figure is bent over a chest of black steel. The lid is open. The chest is empty.'),
        you('Dragith Nurn?'),
        you('What are you doing in Falador? Your tomb is in Lumbridge. And you were destroyed.'),
        say('dragith', 'Destroyed. Yes. And yet, here I am. Again.'),
        say('dragith', 'You. Did you take it?'),
        say('dragith', 'No. Look at you. A fool like you could never have taken the Blade of Memories.'),
        you('The Blade of what?'),
        say('dragith', "It doesn't matter. You'll make a fine servant. In the afterlife, everyone is useful."),
        { duel: 'dragith' },
        say('dragith', 'No... not like this... not again...'),
        nar('Dragith crumbles, and the candles go out one by one.'),
        you('The Blade of Memories.'),
        nar("You look at the chest. Black steel, heavy and cold, with a spiked trim. You've seen this steel before, on the armour of the Kinshra: the Black Knights."),
        nar('The lock has no keyhole you can see. Whoever opened it knew how.'),
        you('Kinshra steel. And a lock only a Kinshra could open.'),
        nar("Among Dragith's things, something white catches the candlelight: an old breastplate, its paint flaking, with the White Knights' crest still on it. Taken from the vault long before the chest."),
        you('Someone should take this back to Sir Amik.'),
        you('Someone will. Eventually.'),
        { reward: 'White Knight Armor', t: 'You pick up the old breastplate.' },
        you("The Black Knights' Fortress, then. North."),
        { end: 'A' },
      ],

      // ----- Road B: follow the cultist -----
      toB: [
        say('cultist', 'A wise choice. The Knights would have locked us both up.'),
        you('They would have locked you up.'),
        say('cultist', 'Details.'),
        you('Where are we going?'),
        say('cultist', "Edgeville. There's talk of a stranger who moved into a house at the edge of town. Keeps to themselves. Never speaks."),
        you('...Never speaks.'),
        say('cultist', 'You know them.'),
        you("We've met."),
        { map: 'b_road' },
      ],
      b_road: [
        { scene: 'The road to Edgeville' },
        nar('A figure in black armour steps out of the trees, bow drawn.'),
        say('scout', 'Halt. Nobody passes this way.'),
        say('cultist', "We're only travellers—"),
        { duel: 'scout' },
        say('scout', 'Fall back! Fall back!'),
        nar('The scout flees into the trees.'),
        { choose: [opt('Did you lead me into a trap?'), opt('Friends of yours?')] },
        say('cultist', "If I'd led you into a trap, would I have stood in front of the arrows?"),
        say('cultist', "That scout would have killed us both. Being a Zamorakian didn't matter to him one bit."),
        you("...That's a fair point."),
        you("I'm still keeping an eye on you."),
        say('cultist', "I'd think less of you if you didn't."),
        { scene: 'Edgeville' },
        nar('Edgeville. A bank, a handful of houses, and the Wilderness just beyond the ditch.'),
        nar("You ask around. Most people don't want to talk about the stranger."),
        nar('At last, an old woman points north, to a house at the very edge of town. "Nobody lives there," she says. "Somebody does anyway."'),
        { map: 'b_house' },
      ],
      b_house: [
        { scene: 'The abandoned house' },
        nar('The house is empty. Dust on the table, cobwebs in the corners.'),
        nar('And red paint, everywhere: on the walls, across the floor, in rings and lines and symbols.'),
        say('cultist', 'A ritual. Some of these are ours. Zamorakian. This one. And this.'),
        say('cultist', "But that's Saradominist. And those, there, are Guthixian. Nobody mixes them. Nobody would want to."),
        say('cultist', 'Whoever did this was trying to—'),
        nar('Something whistles through the air.'),
        nar('The cultist looks down at the blade in his chest. Then he falls.'),
        nar('In the corner, the shadows gather and stand up. Something with no face steps over his body.'),
        say('shadow', '......'),
        { duel: 'shadow' },
        nar('The shadow comes apart like smoke, and seeps down between the floorboards.'),
        you('Down there?'),
        nar('The floorboards lift easily. Too easily, as if someone lifts them often.'),
        nar('Under them is a stone trapdoor with an iron latch.'),
        nar('You lift the latch. Stone stairs lead down into the dark. A voice drifts up from below.'),
        say('melzarv', "Cabbage. Cabbage, cabbage. No, not that one. That one's lying."),
        say('melzarv', "Roots and seeds, roots and seeds. The roots know. The seeds don't."),
        { map: 'b_prison' },
      ],
      b_prison: [
        { scene: 'The hidden prison' },
        nar('At the bottom of the stairs is a small stone cell. A wizard in black robes sits in the corner, talking to the wall.'),
        say('melzarv', "...and that's why you never trust a cabbage with a moustache."),
        say('melzarv', 'You again!'),
        say('melzarv', "By the seeds and roots, I've told you all I know of the Blade of Memories! Seek the dark ones if you want to know more. But me and my feet are done helping you!"),
        nar('He turns around.'),
        you('Melzar? Melzar the Mad?'),
        you('Well. This whole thing has felt like a maze. I suppose that checks out.'),
        say('melzar', "You're not him. You're not him at all."),
        say('melzar', "And if you're not him... then the door is OPEN."),
        { duel: 'melzar' },
        say('melzar', 'Fine! Fine. Keep your door. It was a terrible door anyway.'),
        nar('Melzar sits back down in his corner, and goes back to talking to the wall.'),
        you('The Blade of Memories. And the dark ones.'),
        { choose: [
          opt('Who are the dark ones?', [say('melzar', "Black armour, black hearts, black castle. North. Cabbages won't grow there. Not even the brave ones.")]),
          opt('Who locked you down here?', [say('melzar', 'Him. Him and his shiny red stick. He never says anything. Terrible conversationalist. Worse than the wall.')]),
        ] },
        you('The Kinshra. The Black Knights.'),
        say('melzar', 'Here. Take this. It never suited me.'),
        { reward: 'Cabbage Armor', from: 'melzar' },
        say('melzar', "Mind you don't eat it."),
        you("The Black Knights' Fortress, then."),
        { end: 'B' },
      ],
    },
  };

  const CHAPTERS = [CH1, CH2];
  // what follows each chapter: the next one, or a "coming soon" tease
  const NEXT = { ch1: { id: 'ch2', n: 2, title: 'Falador' }, ch2: { n: 3, title: "The Black Knights' Fortress", soon: true } };

  function make() {
    const byId = Object.fromEntries(CHAPTERS.map((c) => [c.id, c]));
    const chOf = (run) => byId[run.ch];

    // prev: the roads the main story took in earlier chapters ({ ch1: 'A' }), for steps with `when`
    function start(chId, prev) {
      const ch = byId[chId];
      if (!ch) throw new Error(`no chapter ${chId}`);
      const run = { ch: chId, node: ch.start, i: 0, r: null, dots: { [ch.home || 'lumbridge']: 'done' }, at: null, tone: {}, road: null, done: false };
      if (prev && Object.keys(prev).length) run.prev = Object.assign({}, prev);
      skip(run);
      return run;
    }
    // past any step that isn't for this run (a `when` that doesn't match what the earlier chapters did)
    function skip(run) {
      const ch = chOf(run);
      if (!ch || !run.node || run.r) return;
      const list = ch.nodes[run.node] || [];
      const fits = (st) => !st.when || Object.entries(st.when).every(([k, v]) => (run.prev || {})[k] === v);
      while (list[run.i] && !fits(list[run.i])) run.i += 1;
    }

    // the step a run is on, or null past the end of a node (a script fault: every node ends in a stop)
    function raw(run) {
      const ch = chOf(run);
      if (!ch || !run.node) return null;
      const list = ch.nodes[run.node];
      if (!list) return null;
      if (run.r) {
        const st = list[run.r.at];
        const o = st && st.choose && st.choose[run.r.c];
        if (o && run.r.j < o.then.length) return o.then[run.r.j];
        return null;
      }
      return list[run.i] || null;
    }
    function step(run) {
      if (run.done) return { finished: true, road: run.road };
      if (!run.node) return { onMap: true };
      return raw(run) || { broken: true };
    }

    // leave a reply: on to the step after the choice, or the node the option jumps to
    function endReply(run) {
      const ch = chOf(run);
      const o = ch.nodes[run.node][run.r.at].choose[run.r.c];
      run.r = null;
      if (o.go) { run.node = o.go; run.i = 0; } else run.i += 1;
      skip(run);
    }

    function advance(run, pick) {
      if (run.done || !run.node) return;
      const st = raw(run);
      if (!st) return;
      if (st.choose && !run.r) {
        const o = st.choose[pick];
        if (!o) throw new Error('pick an option');
        if (o.tone && st.key) run.tone[st.key] = o.tone;
        if (o.road) run.road = o.road;
        run.r = { at: run.i, c: pick, j: 0 };
        if (!o.then.length) endReply(run);
        return;
      }
      if (st.map) {
        if (run.at) run.dots[run.at] = 'done';
        run.dots[st.map] = 'open';
        run.at = null; run.node = null; run.i = 0; run.r = null;
        return;
      }
      if (st.end) {
        if (run.at) run.dots[run.at] = 'done';
        run.at = null; run.done = true; run.road = st.end;
        return;
      }
      if (run.r) { run.r.j += 1; if (run.r.j >= chOf(run).nodes[run.node][run.r.at].choose[run.r.c].then.length) endReply(run); }
      else run.i += 1;
      skip(run);
    }

    function enter(run, dotId) {
      const d = chOf(run).dots[dotId];
      if (!d || run.dots[dotId] !== 'open' || !d.node) return false;
      run.node = d.node; run.i = 0; run.r = null; run.at = dotId;
      skip(run);
      return true;
    }

    function dotsOf(run) {
      const ch = chOf(run);
      return Object.entries(ch.dots).filter(([id]) => run.dots[id]).map(([id, d]) => Object.assign({ id, state: run.dots[id] }, d));
    }

    // the scene a run is in: the last scene step at or before where it is (for a run loaded from a save)
    function sceneOf(run) {
      const ch = chOf(run);
      if (!run.node) return null;
      const list = ch.nodes[run.node] || [];
      let sc = null;
      for (let k = 0; k <= Math.min(run.i, list.length - 1); k++) {
        if (list[k].scene) sc = list[k];
        else if (list[k].bg && sc) sc = Object.assign({}, sc, { art: list[k].bg });   // a line that changes the painting
      }
      return sc;
    }

    // the duel a run is waiting on, if it is on one
    const duelOf = (run) => { const st = step(run); return st && st.duel ? OPP[st.duel] : null; };

    return { CHAPTERS, OPP, SPEAKERS, NEXT, chapter: (id) => byId[id], start, step, advance, enter, dotsOf, sceneOf, duelOf };
  }

  const api = { make, CHAPTERS, OPP, SPEAKERS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGStory = api;
})(typeof window !== 'undefined' ? window : globalThis);
