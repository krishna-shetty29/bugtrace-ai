package com.bugtrace.backend;

/** No controller or business service depends on a vendor SDK. */
public interface AiProvider {
    record Request(String model,String instructions,String input,int maxOutputTokens){}
    record Usage(Long inputTokens,Long outputTokens,Long totalTokens,String kind){
        public static Usage unavailable(){return new Usage(null,null,null,"UNAVAILABLE");}
    }
    record Response(String model,String requestId,String text,Usage usage,boolean incomplete,String errorCode){
        public Response(String model,String requestId,String text,Usage usage,boolean incomplete){this(model,requestId,text,usage,incomplete,null);}
    }
    final class Failure extends Exception {
        private final String code,requestId;
        public Failure(String code,String safeMessage,String requestId){super(safeMessage);this.code=code;this.requestId=requestId;}
        public String code(){return code;} public String requestId(){return requestId;}
    }
    Response generate(Request request) throws Failure;
}
