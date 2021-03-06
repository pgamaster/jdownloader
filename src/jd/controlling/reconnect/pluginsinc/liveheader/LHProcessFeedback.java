package jd.controlling.reconnect.pluginsinc.liveheader;

import java.io.IOException;
import java.util.Map;

import jd.http.Request;

import org.w3c.dom.Node;

public interface LHProcessFeedback {

    public void onBasicRemoteAPIExceptionOccured(IOException e, Request request) throws ReconnectFailedException;

    public void onVariablesUpdated(Map<String, String> variables) throws ReconnectFailedException;

    public void onVariableParserFailed(String pattern, Request request) throws ReconnectFailedException;

    public void onRequesterror(Request request) throws ReconnectFailedException;

    public void onNewStep(String nodeName, Node toDo) throws ReconnectFailedException;

    public void onRequestOK(Request request) throws ReconnectFailedException;

}
