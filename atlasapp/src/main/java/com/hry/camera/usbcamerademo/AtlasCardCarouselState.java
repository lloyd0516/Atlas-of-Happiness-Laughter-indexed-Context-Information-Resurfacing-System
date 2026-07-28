package com.hry.camera.usbcamerademo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AtlasCardCarouselState {
    enum Role {
        PREVIOUS,
        NEXT,
        CURRENT
    }

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
        if (itemCount == 0) {
            currentIndex = -1;
        } else if (currentIndex < 0 || currentIndex >= itemCount) {
            currentIndex = 0;
        }
    }

    int itemCount() {
        return itemCount;
    }

    int currentIndex() {
        return currentIndex;
    }

    int previousIndex() {
        if (itemCount <= 1) {
            return -1;
        }
        return (currentIndex - 1 + itemCount) % itemCount;
    }

    int nextIndex() {
        if (itemCount <= 1) {
            return -1;
        }
        return (currentIndex + 1) % itemCount;
    }

    boolean movePrevious() {
        int previous = previousIndex();
        if (previous >= 0) {
            currentIndex = previous;
            return true;
        }
        return false;
    }

    boolean moveNext() {
        int next = nextIndex();
        if (next >= 0) {
            currentIndex = next;
            return true;
        }
        return false;
    }

    List<CardSlot> drawOrder() {
        if (itemCount == 0) {
            return Collections.emptyList();
        }

        List<CardSlot> slots = new ArrayList<>();
        if (itemCount == 2) {
            slots.add(new CardSlot(nextIndex(), Role.NEXT));
        } else if (itemCount >= 3) {
            slots.add(new CardSlot(previousIndex(), Role.PREVIOUS));
            slots.add(new CardSlot(nextIndex(), Role.NEXT));
        }
        slots.add(new CardSlot(currentIndex, Role.CURRENT));
        return slots;
    }
}
