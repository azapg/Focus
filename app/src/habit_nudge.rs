use std::collections::BTreeSet;

pub const COOLDOWN_MS: u64 = 2 * 60 * 60 * 1000;
const NOTICE_MS: u64 = 20_000;
const ESCALATE_MS: u64 = 120_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Notice {
    Gentle(usize),
    Locked,
}

impl Notice {
    pub fn text(self) -> String {
        match self {
            Self::Gentle(1) => "1 habit left today".into(),
            Self::Gentle(count) => format!("{count} habits left today"),
            Self::Locked => "Feeds locked until a habit is done".into(),
        }
    }
}

struct Episode {
    start: u64,
    day: i32,
    existing: BTreeSet<String>,
    due: usize,
    cover_requested: bool,
    locked: bool,
}

#[derive(Default)]
pub struct Nudges {
    pub last_sent: u64,
    episode: Option<Episode>,
}

#[derive(Clone, Copy)]
pub struct Sample<'a> {
    pub now: u64,
    pub day: i32,
    pub entered_unfocused: bool,
    pub still_unfocused: bool,
    pub cover_confirmed: bool,
    pub lock_enabled: bool,
    pub due: usize,
    pub completed: &'a BTreeSet<String>,
}

impl Nudges {
    pub fn step(&mut self, s: Sample<'_>) -> Option<Notice> {
        if self.episode.as_ref().is_some_and(|e| {
            e.day != s.day || s.due == 0 || s.now < e.start
                || !s.completed.is_subset(&e.existing)
                || (e.locked && !s.lock_enabled)
                || (!e.locked && !s.still_unfocused)
        }) {
            self.episode = None;
        }
        if s.entered_unfocused && s.due > 0 && self.episode.is_none()
            && (self.last_sent == 0 || s.now.saturating_sub(self.last_sent) >= COOLDOWN_MS)
        {
            self.last_sent = s.now;
            self.episode = Some(Episode {
                start: s.now, day: s.day, existing: s.completed.clone(), due: s.due,
                cover_requested: false, locked: false,
            });
        }
        let episode = self.episode.as_mut()?;
        let elapsed = s.now.saturating_sub(episode.start);
        if episode.cover_requested && s.lock_enabled && s.still_unfocused && s.cover_confirmed {
            episode.locked = true;
        }
        if episode.locked {
            Some(Notice::Locked)
        } else if elapsed < NOTICE_MS {
            Some(Notice::Gentle(episode.due))
        } else {
            None
        }
    }

    pub fn active(&self) -> bool {
        self.episode.is_some()
    }

    pub fn request_cover(&mut self, now: u64) -> bool {
        let Some(episode) = self.episode.as_mut() else { return false };
        if episode.cover_requested || now.saturating_sub(episode.start) < ESCALATE_MS {
            return false;
        }
        episode.cover_requested = true;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_due_habit_can_nudge_once_before_optional_escalation() {
        let mut nudges = Nudges::default();
        let none = BTreeSet::new();
        let sample = |now, entered, due, lock| Sample {
            now, day: 20_000, entered_unfocused: entered, still_unfocused: true,
            cover_confirmed: false, lock_enabled: lock, due, completed: &none,
        };
        assert_eq!(nudges.step(sample(100, true, 0, false)), None);
        assert_eq!(nudges.step(sample(101, true, 1, false)), Some(Notice::Gentle(1)));
        assert_eq!(nudges.step(sample(120_101, false, 1, false)), None);
        assert!(nudges.request_cover(120_101));
        assert_eq!(nudges.step(sample(120_101, false, 1, true)), None);
        assert_eq!(nudges.step(Sample { cover_confirmed: true, ..sample(120_102, false, 1, true) }), Some(Notice::Locked));

        let completed = BTreeSet::from(["sync-checkoff".into()]);
        assert_eq!(nudges.step(Sample { completed: &completed, ..sample(120_103, false, 1, true) }), None);
        assert_eq!(nudges.step(sample(130_000, true, 1, true)), None);
        assert_eq!(nudges.step(sample(COOLDOWN_MS + 101, true, 2, true)), Some(Notice::Gentle(2)));
    }

    #[test]
    fn confirmation_after_focus_returns_cannot_lock_an_episode() {
        let mut nudges = Nudges::default();
        let sample = Sample {
            now: 1, day: 20_000, entered_unfocused: true, still_unfocused: true,
            cover_confirmed: false, lock_enabled: true, due: 1, completed: &BTreeSet::new(),
        };
        assert_eq!(nudges.step(sample), Some(Notice::Gentle(1)));
        assert!(nudges.request_cover(120_001));
        assert_eq!(nudges.step(Sample {
            now: 120_002, entered_unfocused: false, still_unfocused: false,
            cover_confirmed: true, ..sample
        }), None);
        assert!(!nudges.request_cover(120_002));
    }

    #[test]
    fn an_unanswered_cover_cannot_be_requested_again_in_the_same_episode() {
        let mut nudges = Nudges::default();
        let sample = Sample {
            now: 1, day: 20_000, entered_unfocused: true, still_unfocused: true,
            cover_confirmed: false, lock_enabled: true, due: 1, completed: &BTreeSet::new(),
        };
        assert_eq!(nudges.step(sample), Some(Notice::Gentle(1)));
        assert!(!nudges.request_cover(119_999));
        assert_eq!(nudges.step(Sample { now: 120_001, entered_unfocused: false, ..sample }), None);
        assert!(nudges.request_cover(120_001));
        assert_eq!(nudges.step(Sample { now: 130_001, entered_unfocused: false, ..sample }), None);
        assert!(!nudges.request_cover(130_001));
        assert_eq!(nudges.step(Sample { now: 130_002, entered_unfocused: false, still_unfocused: false, ..sample }), None);
        let next = COOLDOWN_MS + 1;
        assert_eq!(nudges.step(Sample { now: next, ..sample }), Some(Notice::Gentle(1)));
        assert!(nudges.request_cover(next + ESCALATE_MS));
    }
}
