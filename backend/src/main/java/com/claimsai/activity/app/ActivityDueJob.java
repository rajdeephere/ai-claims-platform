package com.claimsai.activity.app;

import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import org.springframework.stereotype.Component;

/** Fires at an activity's due time; completing the activity cancels it. */
@Component
public class ActivityDueJob implements JobHandler {

    private final ActivityService activities;

    public ActivityDueJob(ActivityService activities) {
        this.activities = activities;
    }

    @Override
    public String type() {
        return ActivityService.DUE_JOB;
    }

    @Override
    public void handle(JobContext job) {
        activities.escalateIfOpen(((Number) job.payload().get("activityId")).longValue());
    }
}
