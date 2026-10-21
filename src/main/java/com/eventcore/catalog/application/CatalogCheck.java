package com.eventcore.catalog.application;

import com.eventcore.catalog.domain.VersionStatus;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public sealed interface CatalogCheck {

    record Accepted(VersionStatus status) implements CatalogCheck {
    }

    record UnknownType() implements CatalogCheck {
    }

    record UnsupportedVersion(SortedSet<Integer> acceptedVersions) implements CatalogCheck {

        public UnsupportedVersion {
            acceptedVersions = Collections.unmodifiableSortedSet(new TreeSet<>(acceptedVersions));
        }
    }

    record InvalidData(List<String> violations) implements CatalogCheck {

        public InvalidData {
            violations = List.copyOf(violations);
        }
    }
}
