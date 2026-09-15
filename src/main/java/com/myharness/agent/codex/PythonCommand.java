package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/** Runs in LPAC, never on the host. Does not process .pth files or user site configuration. */
final class PythonCommand {
    private PythonCommand() { }

    static List<String> withManagedImports(List<String> argv,ObjectMapper json) throws IOException {
        var args=argv.subList(5,argv.size()); // executable, -I, -S, -X, utf8
        if(args.isEmpty() || args.getFirst().startsWith("-") && !List.of("-c","-m").contains(args.getFirst()))return argv;
        String encoded=Base64.getEncoder().encodeToString(json.writeValueAsBytes(args));
        String bootstrap="""
                import sys, os, runpy, json, base64
                args=json.loads(base64.b64decode('%s'))
                sys.dont_write_bytecode=True
                sys.path.append(os.path.join(os.path.dirname(sys.executable),'Lib','site-packages'))
                if args[0]=='-c':
                    if len(args)<2: raise SystemExit('Python -c requires source')
                    sys.argv=['-c']+args[2:]
                    exec(compile(args[1],'<string>','exec'),{'__name__':'__main__'})
                elif args[0]=='-m':
                    if len(args)<2: raise SystemExit('Python -m requires a module')
                    sys.argv=args[1:]
                    runpy.run_module(args[1],run_name='__main__',alter_sys=True)
                else:
                    filename=os.path.abspath(args[0])
                    sys.path.insert(0,os.path.dirname(filename))
                    sys.argv=[filename]+args[1:]
                    runpy.run_path(filename,run_name='__main__')
                """.formatted(encoded);
        return List.of(argv.getFirst(),"-I","-S","-X","utf8","-c",bootstrap);
    }
}
