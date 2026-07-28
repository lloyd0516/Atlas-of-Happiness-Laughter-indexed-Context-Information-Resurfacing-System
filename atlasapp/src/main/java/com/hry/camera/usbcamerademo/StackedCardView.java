package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Compact horizontal carousel used over the map. The centered card is always the selected event;
 * adjacent cards are previews only, so the card the user sees is also the card that opens.
 */
public class StackedCardView extends FrameLayout {

    public interface Binder {
        void bind(View card, int position);
    }

    public interface OnCardClickListener {
        void onCardClick(int position);
    }

    public interface OnPositionChangedListener {
        void onPositionChanged(int zeroBasedPosition, int total);
    }

    private static final float CARD_SIDE_MARGIN_DP = 24f;
    private static final float SIDE_PEEK_DP = 20f;
    private static final float SWIPE_THRESHOLD_DP = 32f;
    private static final long MOVE_ANIMATION_MS = 180L;

    private int layoutResId;
    private Binder binder;
    private OnCardClickListener clickListener;
    private OnPositionChangedListener positionChangedListener;
    private final AtlasCardCarouselState state = new AtlasCardCarouselState(0);

    private View currentCard;
    private View previousCard;
    private View nextCard;

    private float downX;
    private float downY;
    private float dragDx;
    private final int touchSlop;
    private boolean horizontalDrag;
    private boolean animating;

    public StackedCardView(Context context) {
        this(context, null);
    }

    public StackedCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setClipChildren(true);
        setClipToPadding(true);
    }

    public void setOnCardClickListener(OnCardClickListener listener) {
        clickListener = listener;
    }

    public void setOnPositionChangedListener(OnPositionChangedListener listener) {
        positionChangedListener = listener;
        notifyPositionChanged();
    }

    public void setAdapter(int layoutResId, int itemCount, Binder binder) {
        this.layoutResId = layoutResId;
        this.binder = binder;
        state.setItemCount(itemCount);
        rebuildCards();
    }

    public void refresh(int itemCount) {
        state.setItemCount(itemCount);
        rebuildCards();
    }

    public void showPrevious() {
        if (state.previousIndex() < 0 || animating) {
            return;
        }
        animateCurrentAway(true);
    }

    public void showNext() {
        if (state.nextIndex() < 0 || animating) {
            return;
        }
        animateCurrentAway(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!animating && !horizontalDrag) {
            positionCards();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (state.currentIndex() < 0 || currentCard == null || animating) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragDx = 0f;
                horizontalDrag = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!horizontalDrag
                        && Math.abs(dx) > touchSlop
                        && Math.abs(dx) > Math.abs(dy)) {
                    horizontalDrag = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (horizontalDrag) {
                    dragDx = dx;
                    currentCard.setTranslationX(dragDx);
                    currentCard.setAlpha(1f
                            - Math.min(0.25f, Math.abs(dragDx) / Math.max(1f, getWidth()) * 0.25f));
                }
                return true;

            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (horizontalDrag && Math.abs(dragDx) >= dpToPx(SWIPE_THRESHOLD_DP)) {
                    if (dragDx < 0f) {
                        animateCurrentAway(false);
                    } else {
                        animateCurrentAway(true);
                    }
                } else if (!horizontalDrag
                        && Math.abs(event.getX() - downX) <= touchSlop
                        && Math.abs(event.getY() - downY) <= touchSlop
                        && isInsideCurrentCard(event.getX(), event.getY())) {
                    performClick();
                } else {
                    animateSnapBack();
                }
                horizontalDrag = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                horizontalDrag = false;
                animateSnapBack();
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (clickListener != null && state.currentIndex() >= 0) {
            clickListener.onCardClick(state.currentIndex());
        }
        return true;
    }

    private void rebuildCards() {
        removeAllViews();
        currentCard = null;
        previousCard = null;
        nextCard = null;

        if (layoutResId == 0 || state.itemCount() == 0) {
            notifyPositionChanged();
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (AtlasCardCarouselState.CardSlot slot : state.drawOrder()) {
            View card = inflater.inflate(layoutResId, this, false);
            if (binder != null) {
                binder.bind(card, slot.dataIndex);
            }

            LayoutParams params = new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            int sideMargin = Math.round(dpToPx(CARD_SIDE_MARGIN_DP));
            params.leftMargin = sideMargin;
            params.rightMargin = sideMargin;
            card.setLayoutParams(params);
            card.setClickable(false);
            card.setFocusable(false);
            addView(card);

            if (slot.role == AtlasCardCarouselState.Role.CURRENT) {
                currentCard = card;
            } else if (slot.role == AtlasCardCarouselState.Role.PREVIOUS) {
                previousCard = card;
            } else {
                nextCard = card;
            }
        }

        positionCards();
        notifyPositionChanged();
    }

    private void positionCards() {
        float sideShift = Math.max(
                0f,
                getWidth() - dpToPx(CARD_SIDE_MARGIN_DP) - dpToPx(SIDE_PEEK_DP));
        applySideTransform(previousCard, -sideShift);
        applySideTransform(nextCard, sideShift);

        if (currentCard != null) {
            currentCard.animate().cancel();
            currentCard.setTranslationX(0f);
            currentCard.setScaleX(1f);
            currentCard.setScaleY(1f);
            currentCard.setAlpha(1f);
            currentCard.setElevation(dpToPx(8f));
        }
    }

    private void applySideTransform(View card, float translationX) {
        if (card == null) {
            return;
        }
        card.animate().cancel();
        card.setTranslationX(translationX);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.setAlpha(0.78f);
        card.setElevation(dpToPx(4f));
    }

    private void animateSnapBack() {
        if (currentCard == null) {
            return;
        }
        currentCard.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(MOVE_ANIMATION_MS)
                .start();
    }

    private void animateCurrentAway(final boolean previous) {
        if (currentCard == null) {
            return;
        }
        animating = true;
        float width = Math.max(getWidth(), currentCard.getWidth());
        float targetX = previous ? width : -width;
        currentCard.animate()
                .translationX(targetX)
                .alpha(0.6f)
                .setDuration(MOVE_ANIMATION_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (previous) {
                            state.movePrevious();
                        } else {
                            state.moveNext();
                        }
                        animating = false;
                        rebuildCards();
                    }
                })
                .start();
    }

    private void notifyPositionChanged() {
        if (positionChangedListener != null) {
            positionChangedListener.onPositionChanged(state.currentIndex(), state.itemCount());
        }
    }

    private boolean isInsideCurrentCard(float x, float y) {
        if (currentCard == null) {
            return false;
        }
        float left = currentCard.getLeft() + currentCard.getTranslationX();
        float top = currentCard.getTop() + currentCard.getTranslationY();
        return x >= left
                && x <= left + currentCard.getWidth()
                && y >= top
                && y <= top + currentCard.getHeight();
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
