package com.siteplain.domain.view;

import java.util.List;

public record AboutPageViewModel(
        List<String> methodologyPoints,
        List<String> limitations,
        List<String> dataSources
) {
}
