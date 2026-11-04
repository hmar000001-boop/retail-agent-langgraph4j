package com.example.retail.llm;

public class NoScriptedResponseException extends SimulationException {
    public NoScriptedResponseException(String task, String prompt) {
        super("No scripted rule matched task " + task + ". Add a rule to ScriptedChatModel. Prompt was:\n" + prompt);
    }
}
