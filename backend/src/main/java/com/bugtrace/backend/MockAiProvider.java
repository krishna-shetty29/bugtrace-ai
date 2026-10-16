package com.bugtrace.backend;

/** Deterministic plumbing check; never contacts a model or pretends to diagnose the bug. */
public final class MockAiProvider implements AiProvider {
    @Override public Response generate(Request request){
        String text="MOCK RESPONSE — integration check only.\nThe backend supplied verified evidence and a versioned prompt.\nNo real model was called, no tests were run, and this response does not establish a bug diagnosis.\nReview the saved input evidence IDs and source references below this response.";
        return new Response("mock-v1","mock-response",text,new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
    }
}
