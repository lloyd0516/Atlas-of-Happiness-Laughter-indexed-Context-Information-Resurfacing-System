# Map Memory Card Carousel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the difficult-to-select vertical map card deck with an index-correct horizontal memory carousel that supports full-card taps, side previews, low-threshold swipes, explicit previous/next controls, and a position indicator.

**Architecture:** A pure Java `AtlasCardCarouselState` owns item-count and circular index calculations, including a deterministic back-to-front draw order. `StackedCardView` becomes a horizontal gesture/rendering layer over that state, while `ReviewShellActivity` owns the explicit controls and opens the event at the exact current index.

**Tech Stack:** Android SDK 28, Java, custom `FrameLayout`/`ValueAnimator`, XML layouts, JUnit 4.

## Global Constraints

- Keep map event loading, time ordering, location grouping, AMap rendering, notification focus, and event-detail navigation data unchanged.
- The active visual card and the index sent to `OnCardClickListener` must always refer to the same event.
- Use horizontal swipe only, with an effective threshold of approximately `32dp`.
- Show a centered active card; for 3+ events show previous and next edge previews, for 2 events show only one side preview, and for 1 event show no side preview.
- Add explicit previous/next controls and a `current / total` position indicator.
- Do not add a third-party carousel dependency.
- Do not stage `.superpowers/`, `artifacts/`, `.gradle/`, `atlasapp/build/`, or `local.properties`.
- Do not push without a separate user-authorized action.

---

## File Structure

- Create `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCardCarouselState.java`: Android-free circular index and draw-order model.
- Create `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCardCarouselStateTest.java`: verifies selection, wrapping, 0/1/2-item boundaries, and current-card draw order.
- Modify `atlasapp/src/main/java/com/hry/camera/usbcamerademo/StackedCardView.java`: render one active horizontal card with side previews and handle tap/swipe/button-driven movement.
- Modify `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java`: bind position updates and previous/next controls.
- Modify `atlasapp/src/main/res/layout/activity_review_shell.xml`: reduce the card viewport and add the compact controls row.
- Modify `atlasapp/src/main/res/values/strings.xml`: English accessibility and position strings.
- Modify `atlasapp/src/main/res/values-zh/strings.xml`: Chinese accessibility and position strings.

---

### Task 1: Deterministic Carousel State

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCardCarouselState.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCardCarouselStateTest.java`

**Interfaces:**
- Produces: `AtlasCardCarouselState(int itemCount)`.
- Produces: `void setItemCount(int itemCount)`.
- Produces: `int itemCount()`, `int currentIndex()`, `int previousIndex()`, and `int nextIndex()`.
- Produces: `void movePrevious()` and `void moveNext()`.
- Produces: `List<CardSlot> drawOrder()`, where `CardSlot` contains `int dataIndex` and `Role role`, and the final element is always `Role.CURRENT`.

- [ ] **Step 1: Write failing state tests**

Create `AtlasCardCarouselStateTest` with literal expectations:

```java
@Test
public void nextAndPreviousWrapAcrossFourEvents() {
    AtlasCardCarouselState state = new AtlasCardCarouselState(4);

    assertEquals(0, state.currentIndex());
    assertEquals(3, state.previousIndex());
    assertEquals(1, state.nextIndex());

    state.movePrevious();
    assertEquals(3, state.currentIndex());
    state.moveNext();
    assertEquals(0, state.currentIndex());
}

@Test
public void currentCardIsAlwaysLastInDrawOrder() {
    AtlasCardCarouselState state = new AtlasCardCarouselState(4);
    List<AtlasCardCarouselState.CardSlot> slots = state.drawOrder();

    assertEquals(3, slots.size());
    assertEquals(AtlasCardCarouselState.Role.PREVIOUS, slots.get(0).role);
    assertEquals(AtlasCardCarouselState.Role.NEXT, slots.get(1).role);
    assertEquals(AtlasCardCarouselState.Role.CURRENT, slots.get(2).role);
    assertEquals(state.currentIndex(), slots.get(2).dataIndex);
}

@Test
public void oneEventHasNoSideTargets() {
    AtlasCardCarouselState state = new AtlasCardCarouselState(1);

    assertEquals(-1, state.previousIndex());
    assertEquals(-1, state.nextIndex());
    assertEquals(1, state.drawOrder().size());
    assertEquals(0, state.drawOrder().get(0).dataIndex);
}

@Test
public void twoEventsUseOneSidePreviewWithoutDuplication() {
    AtlasCardCarouselState state = new AtlasCardCarouselState(2);
    List<AtlasCardCarouselState.CardSlot> slots = state.drawOrder();

    assertEquals(2, slots.size());
    assertEquals(AtlasCardCarouselState.Role.NEXT, slots.get(0).role);
    assertEquals(1, slots.get(0).dataIndex);
    assertEquals(AtlasCardCarouselState.Role.CURRENT, slots.get(1).role);
    assertEquals(0, slots.get(1).dataIndex);
}
```

The production mutation caught by these tests is the current bug: drawing or clicking a non-current data index as the visual front card.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCardCarouselStateTest \
  --console=plain
```

Expected: compilation fails because `AtlasCardCarouselState` does not exist.

- [ ] **Step 3: Implement the minimal pure state model**

Implement:

```java
final class AtlasCardCarouselState {
    enum Role { PREVIOUS, NEXT, CURRENT }

    static final class CardSlot {
        final int dataIndex;
        final Role role;

        CardSlot(int dataIndex, Role role) {
            this.dataIndex = dataIndex;
            this.role = role;
        }
    }

    private int itemCount;
    private int currentIndex;

    AtlasCardCarouselState(int itemCount) {
        setItemCount(itemCount);
    }

    void setItemCount(int value) {
        itemCount = Math.max(0, value);
        currentIndex = itemCount == 0 ? -1 : 0;
    }

    int itemCount() {
        return itemCount;
    }

    int currentIndex() {
        return currentIndex;
    }

    int previousIndex() {
        return itemCount <= 1 ? -1 : (currentIndex - 1 + itemCount) % itemCount;
    }

    int nextIndex() {
        return itemCount <= 1 ? -1 : (currentIndex + 1) % itemCount;
    }

    void movePrevious() {
        if (itemCount > 1) {
            currentIndex = previousIndex();
        }
    }

    void moveNext() {
        if (itemCount > 1) {
            currentIndex = nextIndex();
        }
    }

    List<CardSlot> drawOrder() {
        ArrayList<CardSlot> result = new ArrayList<>();
        if (itemCount == 0) {
            return result;
        }
        if (itemCount >= 3) {
            result.add(new CardSlot(previousIndex(), Role.PREVIOUS));
        }
        if (itemCount >= 2) {
            result.add(new CardSlot(nextIndex(), Role.NEXT));
        }
        result.add(new CardSlot(currentIndex, Role.CURRENT));
        return result;
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all `AtlasCardCarouselStateTest` tests pass.

- [ ] **Step 5: Commit only the state task**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCardCarouselState.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCardCarouselStateTest.java
git commit -m "fix: model map card carousel selection"
```

---

### Task 2: Horizontal Card Rendering and Gestures

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/StackedCardView.java`

**Interfaces:**
- Consumes: `AtlasCardCarouselState` and `AtlasCardCarouselState.CardSlot`.
- Preserves: `setAdapter(int layoutResId, int itemCount, Binder binder)`.
- Preserves: `setOnCardClickListener(OnCardClickListener listener)`.
- Produces: `void showPrevious()` and `void showNext()`.
- Produces: `void setOnPositionChangedListener(OnPositionChangedListener listener)`, whose callback is `onPositionChanged(int zeroBasedPosition, int total)`.

- [ ] **Step 1: Replace vertical stack bookkeeping with the tested state**

Remove `topIndex`, `MAX_VISIBLE`, `STACK_OFFSET_DP`, the `GestureDetector`, and vertical drag fields. Add:

```java
private static final float CARD_SIDE_MARGIN_DP = 24f;
private static final float SIDE_PEEK_DP = 20f;
private static final float SWIPE_THRESHOLD_DP = 32f;
private static final long MOVE_ANIMATION_MS = 180L;

private final AtlasCardCarouselState state = new AtlasCardCarouselState(0);
private View currentCard;
private View previousCard;
private View nextCard;
private float downX;
private float downY;
private float dragDx;
private int touchSlop;
private OnPositionChangedListener positionChangedListener;
```

Set `setClickable(true)`, `setClipChildren(false)`, and derive `touchSlop` from `ViewConfiguration`.

- [ ] **Step 2: Rebuild cards back-to-front with the current card last**

In `rebuildStack()`:

```java
removeAllViews();
currentCard = null;
previousCard = null;
nextCard = null;
for (AtlasCardCarouselState.CardSlot slot : state.drawOrder()) {
    View card = inflater.inflate(layoutResId, this, false);
    binder.bind(card, slot.dataIndex);
    LayoutParams params = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    int margin = Math.round(dpToPx(CARD_SIDE_MARGIN_DP));
    params.leftMargin = margin;
    params.rightMargin = margin;
    addView(card, params);
    if (slot.role == AtlasCardCarouselState.Role.CURRENT) {
        currentCard = card;
    } else if (slot.role == AtlasCardCarouselState.Role.PREVIOUS) {
        previousCard = card;
    } else {
        nextCard = card;
    }
}
positionCards();
```

Because `drawOrder()` returns CURRENT last and `addView` appends, the current card is guaranteed to be the top-drawn card.

- [ ] **Step 3: Position the active card and side previews**

Use:

```java
private void positionCards() {
    float travel = Math.max(
            dpToPx(48f),
            getWidth() - dpToPx(CARD_SIDE_MARGIN_DP * 2f + SIDE_PEEK_DP));
    positionSide(previousCard, -travel);
    positionSide(nextCard, travel);
    if (currentCard != null) {
        currentCard.setTranslationX(0f);
        currentCard.setScaleX(1f);
        currentCard.setScaleY(1f);
        currentCard.setAlpha(1f);
        currentCard.setElevation(dpToPx(8f));
    }
}
```

Side cards use scale `0.94f`, alpha `0.78f`, and elevation `4dp`. Call `positionCards()` from `onSizeChanged` so first layout does not depend on a nonzero constructor-time width.

- [ ] **Step 4: Implement full-card tap and horizontal swipe**

Override `onTouchEvent`:

- `ACTION_DOWN`: record `downX/downY`, clear `dragDx`, return `true`.
- `ACTION_MOVE`: update `dragDx`; only translate the current card when `abs(dx) > touchSlop` and `abs(dx) > abs(dy)`. Call `requestDisallowInterceptTouchEvent(true)` only after horizontal intent is established.
- `ACTION_UP`: if horizontal distance exceeds `32dp`, animate left to `showNext()` or right to `showPrevious()`; if movement stays inside touch slop, call `clickListener.onCardClick(state.currentIndex())`; otherwise snap back.
- `ACTION_CANCEL`: snap back.

Do not use raw Y or vertical distance for navigation.

- [ ] **Step 5: Add explicit movement and position callbacks**

Add:

```java
public void showPrevious() {
    if (state.itemCount() <= 1) {
        return;
    }
    state.movePrevious();
    rebuildStack();
    notifyPositionChanged();
}

public void showNext() {
    if (state.itemCount() <= 1) {
        return;
    }
    state.moveNext();
    rebuildStack();
    notifyPositionChanged();
}
```

`setAdapter` calls `state.setItemCount(itemCount)`, rebuilds, then reports the initial position. `OnCardClickListener` must receive `state.currentIndex()` and never an independently stored index.

- [ ] **Step 6: Compile and run the state regression test**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCardCarouselStateTest \
  :atlasapp:compileDebugJavaWithJavac \
  --console=plain
```

Expected: state tests pass and Android source compilation exits `0`.

- [ ] **Step 7: Commit the carousel view**

```bash
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/StackedCardView.java
git commit -m "fix: make map cards easy to select"
```

---

### Task 3: Map Controls and Position Indicator

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java`
- Modify: `atlasapp/src/main/res/layout/activity_review_shell.xml`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: `StackedCardView.showPrevious()`, `showNext()`, and `setOnPositionChangedListener(...)`.
- Produces: visible previous/next buttons and `%1$d / %2$d` position text.

- [ ] **Step 1: Add localized control strings**

English:

```xml
<string name="map_stack_previous">Previous memory</string>
<string name="map_stack_next">Next memory</string>
<string name="map_stack_position">%1$d / %2$d</string>
```

Chinese:

```xml
<string name="map_stack_previous">上一条回忆</string>
<string name="map_stack_next">下一条回忆</string>
<string name="map_stack_position">%1$d / %2$d</string>
```

- [ ] **Step 2: Replace the oversized card viewport with carousel and controls**

Set `mapEventStack` height to `108dp`. Immediately below it add:

```xml
<LinearLayout
    android:id="@+id/mapStackControls"
    android:layout_width="match_parent"
    android:layout_height="40dp"
    android:layout_marginTop="4dp"
    android:gravity="center_vertical"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/btnMapStackPrevious"
        android:layout_width="44dp"
        android:layout_height="36dp"
        android:background="@drawable/atlas_map_stat_pill"
        android:contentDescription="@string/map_stack_previous"
        android:gravity="center"
        android:text="‹"
        android:textColor="@color/mock_text_primary"
        android:textSize="24sp" />

    <TextView
        android:id="@+id/txtMapStackPosition"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:gravity="center"
        android:textColor="@color/mock_text_muted"
        android:textSize="12sp" />

    <TextView
        android:id="@+id/btnMapStackNext"
        android:layout_width="44dp"
        android:layout_height="36dp"
        android:background="@drawable/atlas_map_stat_pill"
        android:contentDescription="@string/map_stack_next"
        android:gravity="center"
        android:text="›"
        android:textColor="@color/mock_text_primary"
        android:textSize="24sp" />
</LinearLayout>
```

- [ ] **Step 3: Bind controls once in `onCreate`**

Add fields for the controls. Bind click listeners to `showPrevious()` and `showNext()`. Register:

```java
mapEventStack.setOnPositionChangedListener(
        new StackedCardView.OnPositionChangedListener() {
            @Override
            public void onPositionChanged(int position, int total) {
                boolean multiple = total > 1;
                mapStackControls.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
                txtMapStackPosition.setText(total > 0
                        ? getString(R.string.map_stack_position, position + 1, total)
                        : "");
                btnMapStackPrevious.setEnabled(multiple);
                btnMapStackNext.setEnabled(multiple);
                btnMapStackPrevious.setAlpha(multiple ? 1f : 0.35f);
                btnMapStackNext.setAlpha(multiple ? 1f : 0.35f);
            }
        });
```

- [ ] **Step 4: Preserve exact event navigation**

Keep the existing callback:

```java
mapEventStack.setOnCardClickListener(new StackedCardView.OnCardClickListener() {
    @Override
    public void onCardClick(int position) {
        openEvent(located.get(position));
    }
});
```

The fixed component guarantees `position` is the same index bound into the visible current card.

- [ ] **Step 5: Build resources and run all unit tests**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

Expected: all JVM tests pass and the Debug APK builds.

- [ ] **Step 6: Commit only the integration files**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/res/layout/activity_review_shell.xml \
  atlasapp/src/main/res/values/strings.xml \
  atlasapp/src/main/res/values-zh/strings.xml
git commit -m "feat: add map memory carousel controls"
```

---

### Task 4: Fresh Verification and OPPO Acceptance

**Files:**
- Verify all Task 1–3 files.
- Do not modify unrelated source or device data.

**Interfaces:**
- Consumes: the assembled Debug APK and the existing four located events on OPPO `PDAM10`.
- Produces: evidence that visual selection, click navigation, swipe, and explicit controls agree.

- [ ] **Step 1: Run clean source checks**

```bash
git diff --check
git status --short
```

Expected: no tracked source changes after commits; only known `.superpowers/` and `artifacts/` remain untracked.

- [ ] **Step 2: Run the complete test suite and Debug build**

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:clean :atlasapp:testDebugUnitTest :atlasapp:assembleDebug \
  --console=plain
```

Expected: exit `0`, zero test failures, and a current Debug APK.

- [ ] **Step 3: Install without clearing moment data**

```bash
/Users/fangjun/Library/Android/sdk/platform-tools/adb -s 64baced7 install -r \
  atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
```

Expected: `Success`; the existing `session_resurfacing_test_20260728` remains available.

- [ ] **Step 4: Verify on the map tab**

Using the four located events:

1. Confirm one full active card and side preview(s) are visible.
2. Confirm `1 / 4` appears and updates after each movement.
3. Tap next and previous; confirm each changes exactly one event.
4. Swipe left approximately `32dp`; confirm the next event becomes active.
5. Swipe right; confirm the previous event becomes active.
6. Tap the active card; confirm the detail title/time matches that visible card.
7. Confirm switching wraps from `4 / 4` to `1 / 4`.
8. Confirm map dragging and zooming still work outside the carousel area.

- [ ] **Step 5: Restore generated tracked files**

```bash
git restore -- .gradle atlasapp/build local.properties
git status --short
```

Expected: only `.superpowers/` and `artifacts/` remain untracked.

- [ ] **Step 6: Keep local branch without pushing**

Report the implementation commits and verification evidence. Do not push until the user explicitly authorizes it.
