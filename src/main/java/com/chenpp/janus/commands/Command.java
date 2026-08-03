package com.chenpp.janus.commands;

/**
 * @author April.Chen
 */
public interface Command {
    /**
     * 执行命令
     *
     * @param args 命令参数
     * @throws Exception 命令执行异常
     */
    void execute(String args) throws Exception;
}