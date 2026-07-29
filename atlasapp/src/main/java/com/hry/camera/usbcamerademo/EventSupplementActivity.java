package com.hry.camera.usbcamerademo;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Requirement 2: three skippable questions (with whom / doing what / mood) for one laughter
 * event, then requirement 5: a delete / save+push / save+no-push decision. "Edit now" from that
 * dialog jumps straight into EventDetailActivity's short-term review for this event.
 */
public class EventSupplementActivity extends AppCompatActivity {
    private static final int STEP_WITH_WHOM = 0;
    private static final int STEP_DOING_WHAT = 1;
    private static final int STEP_MOOD = 2;
    private static final int STEP_COUNT = 3;

    private AtlasReviewRepository repository;
    private String eventId;
    private String sessionId;
    private JSONObject eventJson;
    private ResearchSupplementProgress supplementProgress;

    private int currentStep = STEP_WITH_WHOM;
    private String withWhom = "";
    private String doingWhat = "";
    private String mood = "";

    private TextView txtStep;
    private TextView txtTitle;
    private EditText inputAnswer;
    private Button btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AtlasLocaleManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_supplement);
        repository = new AtlasReviewRepository(this);

        eventId = getIntent().getStringExtra("event_id");
        sessionId = getIntent().getStringExtra("session_id");
        eventJson = repository.loadEventById(sessionId, eventId);
        if (eventJson == null) {
            Toast.makeText(this, R.string.toast_no_event, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        supplementProgress = new ResearchSupplementProgress(STEP_COUNT);
        ResearchInteractionLogger.log(
                this,
                ResearchEventNames.SUPPLEMENT_FLOW_OPENED,
                sessionId,
                eventId,
                null,
                ResearchInteractionLogger.properties(
                        "entry_source",
                        ResearchNavigation.source(
                                getIntent(), "unknown")));

        txtStep = findViewById(R.id.txtSupplementStep);
        txtTitle = findViewById(R.id.txtSupplementTitle);
        inputAnswer = findViewById(R.id.inputSupplementAnswer);
        btnNext = findViewById(R.id.btnSupplementNext);

        findViewById(R.id.btnSupplementSkip).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                ResearchInteractionLogger.log(
                        EventSupplementActivity.this,
                        ResearchEventNames.SUPPLEMENT_STEP_SKIPPED,
                        sessionId,
                        eventId,
                        null,
                        ResearchInteractionLogger.properties(
                                "step_name", stepName(currentStep)));
                supplementProgress.record(currentStep, false);
                advance("");
            }
        });
        btnNext.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                String answer =
                        inputAnswer.getText().toString().trim();
                supplementProgress.record(
                        currentStep, !TextUtils.isEmpty(answer));
                advance(answer);
            }
        });

        renderStep();
    }

    private void renderStep() {
        inputAnswer.setText("");
        switch (currentStep) {
            case STEP_WITH_WHOM:
                txtStep.setText("1 / " + STEP_COUNT);
                txtTitle.setText(R.string.supplement_with_whom_title);
                inputAnswer.setHint(R.string.supplement_with_whom_hint);
                break;
            case STEP_DOING_WHAT:
                txtStep.setText("2 / " + STEP_COUNT);
                txtTitle.setText(R.string.supplement_doing_what_title);
                inputAnswer.setHint(R.string.supplement_doing_what_hint);
                break;
            case STEP_MOOD:
                txtStep.setText("3 / " + STEP_COUNT);
                txtTitle.setText(R.string.supplement_mood_title);
                inputAnswer.setHint(R.string.supplement_mood_hint);
                btnNext.setText(R.string.supplement_save);
                break;
            default:
                break;
        }
    }

    private void advance(String answer) {
        switch (currentStep) {
            case STEP_WITH_WHOM:
                withWhom = answer;
                break;
            case STEP_DOING_WHAT:
                doingWhat = answer;
                break;
            case STEP_MOOD:
                mood = answer;
                break;
            default:
                break;
        }
        if (currentStep < STEP_MOOD) {
            currentStep++;
            renderStep();
        } else {
            boolean saved = repository.updateSocialContext(
                    eventJson, withWhom, doingWhat, mood);
            JSONObject properties = supplementProgress.properties();
            try {
                properties.put("persistence_succeeded", saved);
                properties.put("completion_reason", "questions_finished");
            } catch (Exception ignored) {
            }
            ResearchInteractionLogger.log(
                    this,
                    ResearchEventNames.SUPPLEMENT_FLOW_COMPLETED,
                    sessionId,
                    eventId,
                    null,
                    properties);
            showSaveDecisionDialog();
        }
    }

    private String stepName(int step) {
        switch (step) {
            case STEP_WITH_WHOM:
                return "with_whom";
            case STEP_DOING_WHAT:
                return "doing_what";
            case STEP_MOOD:
                return "mood";
            default:
                return "unknown";
        }
    }

    /** Requirement 5: three mutually-exclusive choices; "edit now" is a separate, optional follow-up. */
    private void showSaveDecisionDialog() {
        final String[] choices = new String[]{
                getString(R.string.save_decision_delete),
                getString(R.string.save_decision_save_push),
                getString(R.string.save_decision_save_no_push)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_decision_title)
                .setCancelable(false)
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            confirmDelete();
                        } else if (which == 1) {
                            applyDecisionAndOfferEdit("save_push");
                        } else {
                            applyDecisionAndOfferEdit("save_no_push");
                        }
                    }
                })
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.save_decision_delete_confirm)
                .setPositiveButton(R.string.save_decision_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String previousAction =
                                repository.getSaveDecisionAction(eventJson);
                        AtlasReviewRepository.EventSummary summary = findSummary();
                        boolean deleted = summary != null
                                && repository.deleteEventPermanently(summary);
                        if (deleted) {
                            logSaveDecisionIfChanged(
                                    previousAction,
                                    "delete");
                            ResearchInteractionLogger.log(
                                    EventSupplementActivity.this,
                                    ResearchEventNames.MOMENT_DELETED,
                                    sessionId,
                                    eventId,
                                    null,
                                    ResearchInteractionLogger.properties(
                                            "delete_source",
                                            "post_session_decision"));
                            AtlasResurfacingManager.refreshLocationsAsync(
                                    EventSupplementActivity.this);
                        }
                        finish();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showSaveDecisionDialog();
                    }
                })
                .show();
    }

    private void applyDecisionAndOfferEdit(String action) {
        String previousAction =
                repository.getSaveDecisionAction(eventJson);
        boolean saved = repository.saveDecision(eventJson, action);
        if (saved) {
            logSaveDecisionIfChanged(previousAction, action);
        }
        AtlasResurfacingManager.refreshLocationsAsync(this);
        new AlertDialog.Builder(this)
                .setMessage(R.string.event_detail_notes_hint)
                .setPositiveButton(R.string.save_decision_edit_now, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ResearchInteractionLogger.log(
                                EventSupplementActivity.this,
                                ResearchEventNames.MOMENT_EDIT_STARTED,
                                sessionId,
                                eventId,
                                null,
                                ResearchInteractionLogger.properties(
                                        "entry_source",
                                        "post_session_decision"));
                        Intent intent = new Intent(EventSupplementActivity.this, EventDetailActivity.class);
                        intent.putExtra("event_id", eventId);
                        intent.putExtra("session_id", sessionId);
                        ResearchNavigation.withSource(
                                intent, "post_session_decision");
                        startActivity(intent);
                        finish();
                    }
                })
                .setNegativeButton(R.string.btn_back, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void logSaveDecisionIfChanged(
            String previousAction,
            String nextAction
    ) {
        JSONObject properties =
                ResearchLogProperties.momentSaveDecision(
                        previousAction,
                        nextAction);
        if (properties == null) {
            return;
        }
        ResearchInteractionLogger.log(
                this,
                ResearchEventNames.MOMENT_SAVE_DECISION,
                sessionId,
                eventId,
                null,
                properties);
    }

    private AtlasReviewRepository.EventSummary findSummary() {
        for (AtlasReviewRepository.EventSummary item : repository.loadEventSummariesForSession(sessionId)) {
            if (eventId.equals(item.eventId)) {
                return item;
            }
        }
        return null;
    }
}
