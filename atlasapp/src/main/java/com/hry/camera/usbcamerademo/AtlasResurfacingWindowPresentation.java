package com.hry.camera.usbcamerademo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure ordering policy shared by aggregate Short- and Long-term cards. */
final class AtlasResurfacingWindowPresentation {
    enum Section {
        LAUGHTER,
        MEDIA,
        LOCATION_DATE,
        CONTEXT_AUDIO,
        SOCIAL_AND_SUMMARY
    }

    private AtlasResurfacingWindowPresentation() {
    }

    static List<Section> visibleSections(
            boolean longTerm,
            boolean expanded,
            boolean hasMedia,
            boolean hasContextAudio) {
        ArrayList<Section> sections = new ArrayList<>();
        sections.add(Section.LAUGHTER);
        if (!longTerm && hasMedia) {
            sections.add(Section.MEDIA);
        }
        sections.add(Section.LOCATION_DATE);
        if (!longTerm && hasContextAudio) {
            sections.add(Section.CONTEXT_AUDIO);
        }
        if (expanded) {
            if (longTerm && hasMedia) {
                sections.add(Section.MEDIA);
            }
            if (longTerm && hasContextAudio) {
                sections.add(Section.CONTEXT_AUDIO);
            }
            sections.add(Section.SOCIAL_AND_SUMMARY);
        }
        return Collections.unmodifiableList(sections);
    }
}
