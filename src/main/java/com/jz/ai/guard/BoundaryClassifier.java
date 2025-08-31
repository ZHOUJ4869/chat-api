package com.jz.ai.guard;

public interface BoundaryClassifier {
    BoundaryVerdict classify(String userMessage);
}