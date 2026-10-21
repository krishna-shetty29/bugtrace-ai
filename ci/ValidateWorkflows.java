import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class ValidateWorkflows {
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    static Map<?,?> object(Object value){require(value instanceof Map,"Expected mapping");return (Map<?,?>)value;}
    public static void main(String[] args)throws Exception {
        var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        var yaml=new Yaml(new SafeConstructor(options));
        String text=Files.readString(Path.of(args[0]));
        var ci=object(yaml.load(text));var triggers=object(ci.get("on"));
        require(triggers.keySet().equals(Set.of("push","pull_request","workflow_dispatch")),"Ordinary triggers changed");
        require(!text.contains("secrets.")&&!text.contains("pull_request_target")&&!text.contains("AllowLiveProvider"),"Ordinary CI may not access live secrets or live jobs");
        var env=object(ci.get("env"));require("mock".equals(env.get("BUGTRACE_AI_PROVIDER")),"Ordinary CI must use mock");
        require("".equals(env.get("OPENAI_API_KEY")),"Ordinary CI must not receive a provider credential");
        var jobs=object(ci.get("jobs"));require(jobs.keySet().equals(Set.of("backend","worker","frontend","plugin")),"Four required jobs must be present");
        for(var value:jobs.values()) {
            var job=object(value);require("ubuntu-24.04".equals(job.get("runs-on")),"Ordinary CI must use hosted runners");
            require(((Number)job.get("timeout-minutes")).intValue()>0,"Job timeout required");
            for(var step:(List<?>)job.get("steps"))require(!Boolean.TRUE.equals(object(step).get("continue-on-error")),"Failures must not be suppressed");
        }
        require(text.contains("clean verify")&&text.contains("npm ci")&&text.contains("npm run build")&&text.contains("coreChecks buildPlugin verifyPluginStructure"),"Build/test commands missing");
        var live=object(yaml.load(Files.readString(Path.of(args[1]))));
        require(object(live.get("on")).keySet().equals(Set.of("workflow_dispatch")),"Live benchmark must be manual only");
        var job=object(object(live.get("jobs")).get("proposal"));
        require("live-benchmarks".equals(job.get("environment")),"Dedicated benchmark environment required");
        require(job.get("if").toString().contains("default_branch"),"Default-branch gate required");
        require(((List<?>)job.get("runs-on")).contains("bugtrace-benchmark"),"Dedicated benchmark runner required");
        System.out.println("PASS: both YAML documents parsed; four hosted CI jobs, mock/no-secret policy, timeouts, failure behavior and manual benchmark isolation validated.");
    }
}
