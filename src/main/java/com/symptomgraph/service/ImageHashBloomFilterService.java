package com.symptomgraph.service;

public interface ImageHashBloomFilterService {

    boolean isEnabled();

    boolean mightContain(String imageHash);

    void add(String imageHash);
}
