package com.bugtrace.backend;

/** Deterministic plumbing check; never contacts a model or pretends to diagnose the bug. */
public final class MockAiProvider implements AiProvider {
    @Override public Response generate(Request request){
        if(request.instructions().equals(BenchmarkReproduction.PLAN_INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var input=mapper.readTree(request.input());
            var ids=new java.util.ArrayList<String>();for(var item:input.path("evidence"))if(ids.size()<4)ids.add(item.path("id").asString());
            var plan=new BenchmarkReproduction.Plan("NEEDS_EVIDENCE","MOCK plumbing response only; no benchmark hypothesis has been evaluated.",ids,java.util.List.of("Use an intentional live provider for model-performance evaluation."),"MOCK intentionally abstains for every case; this is not a scored negative-control result.");
            return new Response("mock-v1","mock-benchmark-plan",mapper.writeValueAsString(plan),new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if(request.instructions().equals(DiagnosisDocument.INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var input=mapper.readTree(request.input());var fields=mapper.readTree(input.get("question").asString());
            var codes=new java.util.ArrayList<DiagnosisDocument.CodeLocation>();
            for(var item:input.path("evidence")) {
                if(codes.size()>=2)break;
                if(!item.path("origin").asString().equals("CURRENT_SNAPSHOT_CODE")||!item.path("sourceRef").asString().endsWith(".java"))continue;
                var lines=item.path("text").asString().split("\\R",-1);int index=0;
                while(index<lines.length&&(lines[index].isBlank()||lines[index].length()>600))index++;
                if(index==lines.length)continue;
                int line=item.path("startLine").asInt()+index;
                codes.add(new DiagnosisDocument.CodeLocation(item.path("id").asString(),item.path("sourceRef").asString(),item.path("revision").asString(),line,line,lines[index],"Current captured source location; inspect surrounding code before accepting the proposed cause."));
            }
            var links=new java.util.ArrayList<DiagnosisDocument.ExecutionLink>();
            for(var execution:fields.path("actualExecutions"))links.add(new DiagnosisDocument.ExecutionLink(java.util.UUID.fromString(execution.path("executionId").asString()),"Stored classification: "+execution.path("classification").asString()+"; observation: "+execution.path("observation").asString()+". Inspect the actual test evidence."));
            var doc=new DiagnosisDocument.Document("MOCK advisory diagnosis: repeated submission may reach charge recording again. This synthetic response does not independently establish the causal explanation.",
                fields.path("report").path("expected_behavior").asString("A logical checkout should record exactly one charge."),
                "The backend supplied an actual expected duplicate-charge assertion from the controlled fake fixture. Review the linked executions and their repetition scope.",
                java.util.List.of("The same logical checkout identifier is reused in the supplied controlled scenarios."),codes,links,
                java.util.List.of("Duplicate handling could fail at the caller, gateway or ledger boundary; inspect the exact selected source and isolate those boundaries before attributing causality.","An incorrect reproduction could miscount charges; inspect its source hash, ordering and per-checkout count assertion."),
                java.util.List.of("Production payment-provider behavior and persistence across restarts remain unverified.","Unseen thread interleavings and rare schedules remain unverified; passing repetitions do not prove absence.","No reference fix was supplied or executed by this diagnosis; no patch is authorized."));
            return new Response("mock-v1","mock-evidence-diagnosis",mapper.writeValueAsString(doc),new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if(request.instructions().equals(ControlledScenarioSource.INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var input=mapper.readTree(request.input());var fields=mapper.readTree(input.get("question").asString());
            String scenario=fields.path("fixedScenario").asString();int repetitions=fields.path("repetitions").asInt();
            var ids=new java.util.ArrayList<String>();for(var item:input.path("evidence"))if(ids.size()<4)ids.add(item.path("id").asString());
            var spec=new GeneratedReproduction.Spec(ControlledScenarioSource.entryPoint(scenario,repetitions),ControlledScenarioSource.ASSERTION,
                ControlledScenarioSource.fault(scenario),ids,ControlledScenarioSource.SIGNATURE,"MOCK generated entry point; the platform controls ordering and actual worker results determine the observed failure rate.");
            return new Response("mock-v1","mock-controlled-scenario",mapper.writeValueAsString(spec),new Usage(null,null,null,"MOCK_NOT_MEASURED"),false);
        }
        if(request.instructions().equals(InvestigationLoopPolicy.INSTRUCTIONS)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var input=mapper.readTree(request.input());var fields=mapper.readTree(input.get("question").asString());
            var ids=new java.util.ArrayList<String>();for(var item:input.path("evidence"))if(ids.size()<4)ids.add(item.get("id").asString());
            boolean review="REVIEW".equals(fields.path("phase").asString());
            String actual=fields.path("actualOutcome").asString("");
            var decision=new InvestigationLoopPolicy.Decision(
                review?"MOCK updated hypothesis after actual outcome: "+actual:"MOCK hypothesis: the selected retry scenario may record another charge for the same checkout.",
                ids,fields.path("fixedScenario").asString("RETRY_AFTER_RESPONSE_LOSS"),review?"FINISH":"CONTINUE",
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
