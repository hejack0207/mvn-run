package fr.lteconsulting.mvnrun;

import java.util.List;
import java.util.ArrayList;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

/**
 * This is the main entry point of the maven run application.
 * 
 * MaenRun allows to run any artifact from the command line. Downloading the artifact and its runtime dependencies are
 * MavenRun's job !
 */
public class MavenRunApp
{

	public static class AppOptions {
		@Parameter(names = {"--artifact", "-a"}, description = "like groupId:artifactId:version", required = true)
		String artifact;

		@Parameter(names = {"--mainclass", "-m"}, description = "main class name, like org.sharpx.cli.Main", required = false)
		String mainClass;

		@Parameter(description = "arguments to main-class")
		List<String> arguments = new ArrayList<String>();

		@Parameter(names = {"--verbose", "-V"}, description = "verbose")
		boolean verbose = false;

		@Parameter(names = {"--help", "-h"}, help = true)
		private boolean help;

	};

	public static class IndexOptions {

	};

	@Parameter(names = {"--verbose", "-V"}, description = "verbose")
	boolean verbose = false;

	@Parameter(names = {"--help", "-h"}, help = true)
	private boolean help;

	public static void main(String[] args) {
		AppOptions appOptions = new AppOptions();
		IndexOptions indexOptions = new IndexOptions();
		MavenRunApp cli = new MavenRunApp();
		JCommander jc = JCommander.newBuilder().addObject(cli).addCommand("app",appOptions).addCommand("index",indexOptions)
				.build();
		jc.parse(args);
		if (cli.help) {
			jc.usage();
			return;
		}

		if("app".equals(jc.getParsedCommand())){
			cli.run(appOptions);
		}else if("index".equals(jc.getParsedCommand())){
			cli.run(indexOptions);
		}

	}

	void run(AppOptions appOptions){
		MavenRun.run(appOptions.artifact, appOptions.arguments.toArray(new String[0]), appOptions.mainClass, !verbose);
	}

	void run(IndexOptions indexOptions){
	}
}
