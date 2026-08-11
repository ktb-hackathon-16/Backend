package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {
    
    private final TrieNode root = new TrieNode();
    
    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");
        normalizedWords.forEach(this::addWord);
        buildFailureLinks();
    }
    
    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        TrieNode current = root;
        for (int index = 0; index < normalizedMessage.length(); index++) {
            char character = normalizedMessage.charAt(index);
            while (current != root && !current.children.containsKey(character)) {
                current = current.failure;
            }
            current = current.children.getOrDefault(character, root);
            if (current.matches) {
                return true;
            }
        }
        return false;
    }

    private void addWord(String word) {
        TrieNode current = root;
        for (int index = 0; index < word.length(); index++) {
            current = current.children.computeIfAbsent(word.charAt(index), ignored -> new TrieNode());
        }
        current.matches = true;
    }

    private void buildFailureLinks() {
        Queue<TrieNode> queue = new ArrayDeque<>();
        root.failure = root;
        for (TrieNode child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            TrieNode current = queue.remove();
            for (Map.Entry<Character, TrieNode> transition : current.children.entrySet()) {
                char character = transition.getKey();
                TrieNode child = transition.getValue();
                TrieNode fallback = current.failure;
                while (fallback != root && !fallback.children.containsKey(character)) {
                    fallback = fallback.failure;
                }
                child.failure = fallback.children.getOrDefault(character, root);
                child.matches |= child.failure.matches;
                queue.add(child);
            }
        }
    }

    private static final class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private TrieNode failure;
        private boolean matches;
    }
}
