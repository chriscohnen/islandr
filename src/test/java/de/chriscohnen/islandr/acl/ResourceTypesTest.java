package de.chriscohnen.islandr.acl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResourceTypes} exists so ResourceDto and DiscoveryDto's @Pattern
 * regexes cannot drift apart the way the raw, duplicated list once could —
 * this only guards the constant itself: well-formed, no duplicates, and the
 * two constants describe the same set.
 */
class ResourceTypesTest {

    @Test
    void patternAndMessageListNameTheSameTypes() {
        Set<String> fromPattern = new HashSet<>(Arrays.asList(ResourceTypes.PATTERN.split("\\|")));
        Set<String> fromMessage = new HashSet<>();
        for (String s : ResourceTypes.LIST_FOR_MESSAGE.split(",")) fromMessage.add(s.strip());
        assertThat(fromPattern).isEqualTo(fromMessage);
    }

    @Test
    void noDuplicateTypes() {
        List<String> types = Arrays.asList(ResourceTypes.PATTERN.split("\\|"));
        assertThat(types).doesNotHaveDuplicates();
    }

    @Test
    void everyKnownTypeIsPresent() {
        assertThat(ResourceTypes.PATTERN).contains(
                "computer", "router", "accesspoint", "printer", "nas", "camera",
                "iot", "virt-host", "rackserver", "kvm", "management", "other");
    }
}
