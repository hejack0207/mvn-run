package fr.lteconsulting.mvnrun;

import java.util.List;
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

	public static void main(String[] args) {
		MavenRunApp cli = new MavenRunApp();
		JCommander jc = JCommander.newBuilder().addObject(cli).build();
		jc.parse(args);
		if (cli.help) {
			jc.usage();
			return;
		}

		cli.run();
	}

	void run(){
		MavenRun.run(artifact, arguments.toArray(new String[0]), mainClass, !verbose);
	}
}
