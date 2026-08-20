/**
 */
package com.interview.agent.graph;

/**
 * 用户主动终止面试异常（与 Go 版本 ErrUserQuit 一致）
 */
public class UserQuitException extends Exception {
    public UserQuitException() {
        super("用户主动终止面试");
    }
}
