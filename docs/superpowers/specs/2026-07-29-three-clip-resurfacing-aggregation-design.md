# Three-Clip Resurfacing Aggregation Design

**Date:** 2026-07-29

**Branch:** `fj_aggregate_ver`

## Goal

Replace the current one-card-per-laughter presentation with one card per
three-clip recording bucket. A bucket may contain multiple laughter audio
files, one automatic capture bundle, and multiple possible related speech
context files. Automatic photo/video capture and resurfacing presentation
must use the same bucket boundaries.

For the default 30-second audio clip configuration, each bucket represents
90 seconds. The rule is intentionally expressed as three clips rather than
as a fixed 90-second constant:

```text
aggregation_window_sec = session_clip_duration_sec * 3
bucket_id = floor(session_relative_audio_time_sec / aggregation_window_sec)
```

Examples:

| Session clip duration | Aggregation window |
| --- | --- |
| 20 seconds | 60 seconds |
| 30 seconds | 90 seconds |
| 45 seconds | 135 seconds |

## Confirmed Product Decisions

- A recording session is divided into fixed, non-overlapping three-clip
  buckets starting at session-relative time zero.
- Bucket intervals are left-closed and right-open. With the default
  configuration, `89.999s` is in bucket 0 and `90.000s` is in bucket 1.
- The automatic capture threshold changes from four clips to three clips.
- The first eligible laughter in a bucket may trigger one automatic capture
  bundle. Later laughter in the same bucket must not trigger another bundle.
- One automatic capture bundle remains fixed at two photos and one video.
- A capture bundle belongs to exactly one bucket and is rendered exactly once.
  Media must never be borrowed from a neighboring bucket.
- A resurfacing card is created only for a bucket containing at least one
  laughter audio item.
- Multiple laughter audio items in one bucket are displayed as separate,
  independently playable rows ordered by occurrence time.
- A possible-related-speech context item follows a related laughter bucket
  instead of creating a context-only card.
- Each context audio item is assigned to at most one resurfacing bucket and
  is never reused across cards.
- Event-level social context and user summary behavior remain unchanged.
- Both short-term and long-term cards retain one shared
  `View more details / Collapse` control. They must not be split into
  independent collapsible sections.

## Architecture

### Shared bucket policy

Introduce a small pure policy component responsible for the common
three-clip calculation. Both automatic capture and resurfacing aggregation
must call this component instead of independently reproducing the formula.

The policy consumes the session's audio clip duration and exposes:

- the fixed bucket size in seconds and milliseconds;
- the bucket ID for a session-relative audio timestamp;
- the bucket start and end time;
- boundary-safe behavior for timestamps exactly on a bucket edge.

The session clip duration is snapshotted at recording-session start. Review
code must read the duration saved with that original session, not the user's
current global setting. Changing settings after a recording must not regroup
historical moments.

### Window aggregation model

Add a pure `AtlasResurfacingWindowAggregator`-style component between the
normalized repository data and `EventDetailActivity`. It returns ordered
window models instead of making the Activity group raw JSON directly.

Each window model contains:

- bucket ID;
- session-relative and wall-clock start/end times;
- ordered laughter audio items;
- zero or one automatic capture bundle;
- ordered, uniquely assigned possible-related-speech audio items;
- total laughter duration;
- a stable anonymous window identity for interaction logs.

This keeps grouping, legacy fallback, ownership, ordering, and duration
calculation independently testable. `EventDetailActivity` remains
responsible for Android view inflation, playback controls, expansion state,
editing, deletion, and research interaction logging.

### Capture metadata

New capture records must persist the bucket identity explicitly alongside
the existing bundle metadata:

- `automation_bucket_id`;
- `automation_bucket_clip_count` with the value `3`;
- `automation_bucket_duration_sec`;
- `bundle_id`;
- `bundle_trigger_time_ms`;
- `bundle_media_index`.

The video and both photos from one capture request carry the same bucket ID.
Partial save success is allowed: a bucket can contain only the successfully
saved members of its bundle, but it must not borrow missing members from
another bundle.

## Aggregation Rules

### Laughter audio

Each laughter audio file is assigned to the bucket containing the
Speechmatics laughter event start time. It appears once and only once.

Within a window, laughter rows are sorted by:

1. event start time;
2. path as a deterministic tie-breaker.

The left-side duration badge displays the total detected laughter duration
inside the bucket, not the bucket length and not the padded WAV length.
Overlapping Speechmatics laughter intervals are merged before their lengths
are summed so overlapping time is not counted twice. For legacy items that
only expose `duration_sec`, the aggregator uses the available duration as a
deterministic fallback.

Each laughter file retains its own play/pause button, real waveform, progress
indicator, playback gain behavior, and media interaction logs.

### Photos and video

New data uses the explicit `automation_bucket_id`. The entire two-photo plus
one-video bundle is owned by that single bucket and rendered once.

The current "nearest media within plus or minus 90 seconds" selection must
not be used by the aggregated cards. It would permit cross-window borrowing
and repeated media. Existing bundle integrity rules remain:

- show at most two photos and one video;
- preserve photo media-index order;
- do not fill a partial bundle from a neighboring bundle;
- omit missing files without affecting accessible files.

### Possible related speech context

New recordings must retain enough metadata to connect each
`possible_related_speech_context` item with its related laughter clip IDs.
The aggregator assigns the context item to one target laughter bucket:

1. consider only explicitly related laughter items when explicit links exist;
2. otherwise, for legacy data, consider laughter items inside the session's
   configured context-neighbor distance;
3. choose the laughter item with the smallest absolute time distance;
4. if distances are equal, choose the earlier laughter bucket;
5. if still tied, use stable bucket/path ordering.

After assignment, the context item is consumed and cannot appear in another
window. Context files with no valid related laughter are not rendered and do
not create their own cards.

Multiple distinct context files may be assigned to one bucket. They are
shown as separate independently playable rows ordered by context start time.
Context playback continues to use the original audio without the
laughter-only gain preparation.

## Short-Term Presentation

Each card represents one three-clip bucket. The default order is:

1. all laughter audio rows;
2. the bucket's photo/video preview;
3. event date and location;
4. all longer-audio rows backed by
   `possible_related_speech_context`.

The single collapsed section retains:

- Social context;
- User summary and its existing note-entry behavior.

If the bucket has no successfully saved media, the photo/video region is
omitted without a placeholder. If it has no context audio, the longer-audio
region is omitted.

## Long-Term Presentation

The default order is:

1. all laughter audio rows;
2. event date and location.

The existing single collapsed section contains, in order:

1. Photos/videos;
2. Longer audio backed by `possible_related_speech_context`;
3. Social context;
4. User summary and its existing note-entry behavior.

The long-term card must continue to use one shared expansion button rather
than four independent toggles. The current long-term social-tag pill is not
shown outside the collapsed details because Social context belongs inside
that section.

## Historical Data Compatibility

The repository enriches normalized review data with the original session
start time and session clip duration from `summary.json`.

Bucket ownership is resolved in this order:

### Laughter and context audio

1. explicit bucket ID, if present;
2. `start_sec`;
3. `device_time_ms - session_start_ms`;
4. `clip_id * session_clip_duration_sec`.

### Capture bundles

1. explicit `automation_bucket_id` when
   `automation_bucket_clip_count == 3`;
2. `bundle_trigger_time_ms - session_start_ms`;
3. capture time minus session start.

The numeric bucket suffix embedded in an old `bundle_id` must not be treated
as a new bucket ID. Existing versions calculated that suffix with the
four-clip capture threshold, so reusing it would put historical media into
the wrong three-clip window.

An item whose bucket cannot be determined is omitted from aggregated cards.
It must not be placed into bucket zero by guesswork and must not be borrowed
by another window.

Legacy context audio without explicit laughter links uses the unique nearest
assignment rule described above. Legacy media without explicit bundle
metadata continues to use the existing deterministic bundle inference first,
then the inferred bundle is assigned to one bucket.

## Interaction Logging

- Existing `media_opened`, `media_play_started`, `media_play_paused`,
  `media_play_completed`, and failure events remain per physical media file.
- Laughter and context audio keep distinct media types.
- Expansion and collapse events identify the aggregate card with an
  anonymous `window_id` instead of treating the card as one laughter clip.
- The expanded-duration timer remains per card.
- Photo and video interactions remain per physical file.
- User-summary editing and moment decision logs remain event-level and retain
  their existing append-only behavior.

No sensitive social-context or user-summary content is added to research
interaction logs.

## Failure and Empty States

- Missing audio disables only that audio row.
- Missing bundle members are omitted; no cross-bucket fill is permitted.
- A window with laughter but no visual media still renders its laughter,
  date/location, and any context audio.
- A window with no context audio omits the longer-audio region.
- An event with no valid laughter windows uses the existing empty state.
- Malformed or unresolved historical items are skipped deterministically and
  must not crash the detail page.

## Test Strategy

### Bucket policy tests

- three 30-second clips produce a 90-second bucket;
- three 20-second clips produce a 60-second bucket;
- three 45-second clips produce a 135-second bucket;
- values immediately before and exactly on a boundary enter different
  buckets;
- capture and review callers receive the same bucket ID for the same
  session-relative timestamp.

### Aggregator tests

- multiple laughter items in one bucket produce one ordered window;
- laughter in adjacent buckets produces separate windows;
- a window is not created for context-only data;
- overlapping laughter intervals contribute their union duration;
- non-overlapping laughter durations are summed;
- one explicit capture bundle appears in one window only;
- legacy four-clip bucket suffixes are ignored in favor of capture time;
- no media is borrowed across a bucket boundary;
- a partial bundle remains partial;
- context follows explicitly related laughter;
- each context item is assigned at most once;
- a context item related to laughter in multiple buckets chooses the nearest
  laughter, then the earlier bucket on a tie;
- legacy audio and media fallbacks use the original session clip duration;
- unresolved legacy assets are omitted instead of guessed.

### Presentation and regression tests

- the short-term presentation model orders laughter, media, date/location,
  context, then the collapsed social/summary section;
- the long-term presentation model keeps laughter and date/location visible
  and places media, context, social, and summary inside one collapsed section;
- independent audio playback bindings remain available for every laughter
  and context row;
- existing media playback logging, note editing, event deletion, notification
  navigation, and two-photo plus one-video bundle behavior continue to pass.

The final verification requires the complete JVM unit-test suite and a fresh
debug APK build.

## Out of Scope

- Changing the rule-based laughter WAV extraction or its 2.5-second padding;
- changing the 30-second default audio clip configuration;
- changing Speechmatics detection settings;
- splitting Long-term or Short-term details into independent collapsible
  sections;
- combining multiple laughter WAV files into a new audio file;
- changing notification selection or scheduling;
- changing event-level social context or user-summary semantics.
