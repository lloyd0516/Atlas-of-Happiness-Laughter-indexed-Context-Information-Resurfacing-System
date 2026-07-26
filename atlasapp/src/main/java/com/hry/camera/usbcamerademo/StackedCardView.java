package com.hry.camera.usbcamerademo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Requirement 3 (map view redesign): a real swipeable card stack, like materials/地图组织视图.jpg's
 * "最近记录" deck — up to 3 cards visible at once, front card offset slightly less than the ones
 * behind it, drag up/down (or left/right) to bring the next card to the front.
 *
 * This is a from-scratch gesture view rather than a plain vertical list, per user confirmation
 * that a real stacked-card interaction (not a simple scroll list) was wanted.
 */
public class StackedCardView extends FrameLayout {

    public interface Binder {
        void bind(View card, int position);
    }

    public interface OnCardClickListener {
        void onCardClick(int position);
    }

    private static final int MAX_VISIBLE = 3;
    private static final float STACK_OFFSET_DP = 10f;
    private static final float STACK_SCALE_STEP = 0.04f;
    private static final float SWIPE_THRESHOLD_DP = 60f;

    private int layoutResId;
    private int itemCount;
    private Binder binder;
    private OnCardClickListener clickListener;
    private final List<View> activeCards = new ArrayList<>();
    private int topIndex = 0;

    private GestureDetector gestureDetector;
    private View draggingCard;
    private float dragStartY;
    private float dragCurrentDy;
    private boolean isDragging;

    public StackedCardView(Context context) {
        super(context);
        init();
    }

    public StackedCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (clickListener != null && !activeCards.isEmpty()) {
                    clickListener.onCardClick(topIndex);
                }
                return true;
            }
        });
    }

    public void setOnCardClickListener(OnCardClickListener listener) {
        this.clickListener = listener;
    }

    public void setAdapter(int layoutResId, int itemCount, Binder binder) {
        this.layoutResId = layoutResId;
        this.itemCount = itemCount;
        this.binder = binder;
        this.topIndex = 0;
        rebuildStack();
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void rebuildStack() {
        removeAllViews();
        activeCards.clear();
        if (itemCount <= 0 || layoutResId == 0) {
            return;
        }
        int visible = Math.min(MAX_VISIBLE, itemCount);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        // Add back-to-front so the first item ends up drawn on top.
        for (int slot = visible - 1; slot >= 0; slot--) {
            int dataIndex = (topIndex + slot) % itemCount;
            View card = inflater.inflate(layoutResId, this, false);
            if (binder != null) {
                binder.bind(card, dataIndex);
            }
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            card.setLayoutParams(params);
            applyStackTransform(card, slot);
            addView(card, 0);
            activeCards.add(0, card);
        }
        // Front card (slot 0) is the last one added at index size-1 in activeCards after the loop
        // above; re-fetch it explicitly for touch handling.
        View front = getFrontCard();
        if (front != null) {
            wireFrontCardTouch(front);
        }
    }

    private View getFrontCard() {
        if (activeCards.isEmpty()) {
            return null;
        }
        return activeCards.get(activeCards.size() - 1);
    }

    private void applyStackTransform(View card, int slotFromFront) {
        float offset = dpToPx(STACK_OFFSET_DP) * slotFromFront;
        card.setTranslationY(offset);
        float scale = 1f - STACK_SCALE_STEP * slotFromFront;
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setAlpha(slotFromFront == 0 ? 1f : 0.92f - 0.08f * slotFromFront);
        card.setElevation(dpToPx(8 - slotFromFront));
    }

    private void wireFrontCardTouch(final View front) {
        front.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (gestureDetector.onTouchEvent(event)) {
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingCard = front;
                        dragStartY = event.getRawY();
                        dragCurrentDy = 0f;
                        isDragging = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (draggingCard == null) {
                            return false;
                        }
                        dragCurrentDy = event.getRawY() - dragStartY;
                        draggingCard.setTranslationY(dragCurrentDy);
                        float progress = Math.min(1f, Math.abs(dragCurrentDy) / dpToPx(120f));
                        draggingCard.setAlpha(1f - progress * 0.5f);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (draggingCard == null) {
                            return false;
                        }
                        boolean swiped = Math.abs(dragCurrentDy) > dpToPx(SWIPE_THRESHOLD_DP);
                        if (swiped) {
                            animateSwipeAway(draggingCard, dragCurrentDy < 0);
                        } else {
                            animateSnapBack(draggingCard);
                        }
                        draggingCard = null;
                        isDragging = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void animateSnapBack(final View card) {
        ValueAnimator animator = ValueAnimator.ofFloat(card.getTranslationY(), 0f);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                card.setTranslationY((Float) animation.getAnimatedValue());
                card.setAlpha(1f);
            }
        });
        animator.setDuration(180);
        animator.start();
    }

    private void animateSwipeAway(final View card, boolean upward) {
        float targetY = upward ? -getHeight() : getHeight();
        ValueAnimator animator = ValueAnimator.ofFloat(card.getTranslationY(), targetY);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                card.setTranslationY((Float) animation.getAnimatedValue());
            }
        });
        animator.setDuration(220);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                advanceStack();
            }
        });
        animator.start();
    }

    private void advanceStack() {
        if (itemCount <= 0) {
            return;
        }
        topIndex = (topIndex + 1) % itemCount;
        rebuildStack();
    }

    public void refresh(int itemCount) {
        this.itemCount = itemCount;
        this.topIndex = 0;
        rebuildStack();
    }
}
