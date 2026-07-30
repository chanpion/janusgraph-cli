package com.example.janus.commands;

public interface Command {
    void execute(String args) throws Exception;
}