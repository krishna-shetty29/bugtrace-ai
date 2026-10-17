package com.bugtrace.backend;

/** Deterministic plumbing check; never contacts a model or pretends to diagnose the bug. */
public final class MockAiProvider implements AiProvider {
    @Override public Response generate(Request request){
        if(request.instructions().equals(InvestigationLoopPolicy.INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var input=mapper.readTree(request.input());var fields=mapper.readTree(input.get("question").asString());
            var ids=new java.util.ArrayList<String>();for(var item:input.path("evidence"))if(ids.size()<4)ids.add(item.get("id").asString());
            boolean review="REVIEW".equals(fields.path("phase").asString());
            String actual=fields.path("actualOutcome").asString("");
            var decision=new InvestigationLoopPolicy.Decision(
                review?"MOCK updated hypothesis after actual outcome: "+actual:"MOCK hypothesis: retry may record another charge after the first response is lost.",
                ids,"RETRY_AFTER_RESPONSE_LOSS",review?"FINISH":"CONTINUE",
                review?"MOCK summary; inspect the actual generated-test report. This establishes no verified diagnosis or fix.":"MOCK proposes the fixed trusted retry experiment; no test has run yet.",java.util.List.of());
            return new Response("mock-v1","mock-bounded-loop",mapper.writeValueAsString(decision),new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if(request.instructions().equals(GeneratedReproduction.INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var ids=new java.util.ArrayList<String>();
            for(var item:mapper.readTree(request.input()).path("evidence"))if(ids.size()<4)ids.add(item.get("id").asString());
            var spec=new GeneratedReproduction.Spec(GeneratedReproduction.exampleSource(),GeneratedReproduction.ASSERTION,
                GeneratedReproduction.FAULT,ids,GeneratedReproduction.SIGNATURE,"MOCK deterministic test draft for the trusted fixture; only actual execution can establish its result.");
            return new Response("mock-v1","mock-generated-reproduction",mapper.writeValueAsString(spec),new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if (request.instructions().startsWith("You are a bounded role coordinated by backend Java code.")) {
            return new Response("mock-v1","mock-role", """
                {"summary":"MOCK — role plumbing only; no real diagnosis, generated artifact or verified fix.",
                 "evidenceIds":[],"missingEvidence":["Use real retrieved evidence and actual execution results for a useful proposal."],
                 "testDraft":"","patchDraft":"","checks":[],"experimentType":"REQUEST_MORE_EVIDENCE","toolRequests":[]}
                """,new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if (request.instructions().equals(EvidenceAnalyzerController.INSTRUCTIONS)) {
            String output = """
                {"hypothesis":"MOCK — insufficient evidence is deliberately reported by this integration fixture.",
                 "evidenceIds":[],
                 "experimentType":"REQUEST_MORE_EVIDENCE",
                 "fileReferences":[],
                 "suggestedExperiment":"Manually inspect selected payment, retry and test evidence before proposing a real experiment.",
                 "missingEvidence":["Mock output is synthetic and does not diagnose the selected repository."]}
                """;
            return new Response("mock-v1", "mock-hypothesis", output,
                new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        String text="MOCK RESPONSE — integration check only.\nThe backend supplied verified evidence and a versioned prompt.\nNo real model was called, no tests were run, and this response does not establish a bug diagnosis.\nReview the saved input evidence IDs and source references below this response.";
        return new Response("mock-v1","mock-response",text,new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
    }
}
