package dev.denrzv.codereview.orchestration.workflow;

import dev.denrzv.codereview.adapter.api.ReviewController;

record InvalidWorkflowDependency(ReviewController controller) {
}
