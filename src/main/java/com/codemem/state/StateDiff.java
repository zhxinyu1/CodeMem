package com.codemem.state;

import java.util.List;

public record StateDiff(
        int fromVersion,
        int toVersion,
        List<String> changedPaths,
        ProjectState before,
        ProjectState after
) {
}