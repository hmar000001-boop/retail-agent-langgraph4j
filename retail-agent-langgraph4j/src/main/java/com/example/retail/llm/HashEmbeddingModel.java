package com.example.retail.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Deterministic bag-of-words hashing embeddings: free, offline, and identical on every machine. */
public class HashEmbeddingModel implements EmbeddingModel {
    private static final int DIM = 256;
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "for", "with", "and", "to", "of", "in", "on", "i", "me", "my", "need", "want", "some", "please");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        int i = 0;
        for (String text : request.getInstructions()) {
            out.add(new Embedding(vector(text), i++));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIM;
    }

    static float[] vector(String text) {
        float[] v = new float[DIM];
        if (text != null) {
            for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
                if (raw.length() < 2 || STOP.contains(raw)) continue;
                String token = raw.length() > 3 && raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw;
                v[Math.floorMod(token.hashCode(), DIM)] += 1f;
            }
        }
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < DIM; i++) v[i] /= (float) norm;
        return v;
    }
}
