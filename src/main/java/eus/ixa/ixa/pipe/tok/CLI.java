/*
 *Copyright 2015 Rodrigo Agerri

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package eus.ixa.ixa.pipe.tok;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.jdom2.JDOMException;

import ixa.kaflib.KAFDocument;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;

/**
 * ixa-pipe-tok provides several configuration parameters:
 * 
 * <ol>
 * <li>lang: choose language to create the lang attribute in KAF header.
 * <li>normalize: choose normalization method.
 * <li>outputFormat: choose between oneline, conll or NAF as output.
 * <li>untokenizable: print untokenizable (\uFFFD) characters.
 * <li>notok: take already tokenized text as input and create a KAFDocument with
 * it.
 * <li>inputkaf: take a NAF Document as input instead of plain text file.
 * <li>kafversion: specify the NAF version as parameter.
 * <li>segmentOnLinebreak: segment on each linebreak, even if there is no final punctuation mark.
 * <li>eval: input reference corpus to evaluate a tokenizer.
 * </ol>
 * 
 * 
 * @author ragerri
 * @version 2015-04-08
 */

public class CLI {

  /**
   * Get dynamically the version of ixa-pipe-tok by looking at the MANIFEST
   * file.
   */
  private final String version = CLI.class.getPackage()
      .getImplementationVersion();
  /**
   * Get the commit of ixa-pipe-tok by looking at the MANIFEST file.
   */
  private final String commit = CLI.class.getPackage()
      .getSpecificationVersion();
  Namespace parsedArguments = null;

  // create Argument Parser
  ArgumentParser argParser = ArgumentParsers.newArgumentParser(
      "ixa-pipe-tok-" + version + ".jar").description(
      "ixa-pipe-tok-" + version
          + " is a multilingual tokenizer developed by the IXA NLP Group.\n");
  /**
   * Sub parser instance.
   */
  private final Subparsers subParsers = argParser.addSubparsers().help(
      "sub-command help");
  /**
   * The parser that manages the tagging sub-command.
   */
  private final Subparser annotateParser;
  /**
   * The parser that manages the evaluation sub-command.
   */
  private final Subparser evalParser;
  /**
   * Parser to start TCP socket for server-client functionality.
   */
  private Subparser serverParser;
  /**
   * Sends queries to the serverParser for annotation.
   */
  private Subparser clientParser;

  public CLI() {
    annotateParser = subParsers.addParser("tok").help("Tagging CLI");
    loadAnnotateParameters();
    evalParser = subParsers.addParser("eval").help("Evaluation tokenisation output against reference file in oneline format");
    loadEvalParameters();
    serverParser = subParsers.addParser("server").help("Start TCP socket server");
    loadServerParameters();
    clientParser = subParsers.addParser("client").help("Send queries to the TCP socket server");
    loadClientParameters();
  }

  public static void main(final String[] args) throws IOException,
      JDOMException {

    final CLI cmdLine = new CLI();
    cmdLine.parseCLI(args);
  }

  /**
   * Parse the command interface parameters with the argParser.
   * 
   * @param args
   *          the arguments passed through the CLI
   * @throws IOException
   *           exception if problems with the incoming data
   * @throws JDOMException
   *           a xml exception
   */
  public final void parseCLI(final String[] args) throws IOException,
      JDOMException {
    try {
      parsedArguments = argParser.parseArgs(args);
      if (parsedArguments.getString("verbose").equalsIgnoreCase("yes")) {
    	  System.err.println("CLI options: " + parsedArguments);
      }
      if (args[0].equals("tok")) {
        annotate(System.in, System.out);
      }  else if (args[0].equals("eval")) {
    	  eval(System.in, System.out);
      } 
      else if (args[0].equals("server")) {
        server();
      } else if (args[0].equals("client")) {
        client(System.in, System.out);
      }
    } catch (final ArgumentParserException e) {
      argParser.handleError(e);
      System.out.println("Run java -jar target/ixa-pipe-tok-" + version
          + ".jar (tok|eval|server|client) -help for details");
      System.exit(1);
    }
  }

  public final void annotate(final InputStream inputStream,
      final OutputStream outputStream) throws IOException, JDOMException {
    final String outputFormat = parsedArguments.getString("outputFormat");
    final String normalize = parsedArguments.getString("normalize");
    final String lang = parsedArguments.getString("lang");
    final String untokenizable = parsedArguments.getString("untokenizable");
    final String kafVersion = parsedArguments.getString("kafversion");
    final Boolean inputKafRaw = parsedArguments.getBoolean("inputkaf");
    final Boolean noTok = parsedArguments.getBoolean("notok");
    final String segmentOnLinebreak = parsedArguments.getString("segmentOnLinebreak");
    final String verbose = parsedArguments.getString("verbose");
    final Properties properties = setAnnotateProperties(lang, normalize, untokenizable, segmentOnLinebreak, verbose);
    BufferedReader breader = null;
    final BufferedWriter bwriter = new BufferedWriter(new OutputStreamWriter(
        System.out, "UTF-8"));
    KAFDocument kaf;

    if (noTok) {
      final BufferedReader noTokReader = new BufferedReader(
          new InputStreamReader(System.in, "UTF-8"));
      kaf = new KAFDocument(lang, kafVersion);
      final KAFDocument.LinguisticProcessor newLp = kaf.addLinguisticProcessor(
          "text", "ixa-pipe-tok-notok-" + lang, version + "-" + commit);
      newLp.setBeginTimestamp();
      Annotate.tokensToKAF(noTokReader, kaf);
      newLp.setEndTimestamp();
      bwriter.write(kaf.toString());
      noTokReader.close();
    } else {
      if (inputKafRaw) {
        final BufferedReader kafReader = new BufferedReader(
            new InputStreamReader(System.in, "UTF-8"));
        // read KAF from standard input
        kaf = KAFDocument.createFromStream(kafReader);
        final String text = kaf.getRawText();
        final StringReader stringReader = new StringReader(text);
        breader = new BufferedReader(stringReader);
      } else {
        kaf = new KAFDocument(lang, kafVersion);
        breader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
      }
      final Annotate annotator = new Annotate(breader, properties);
      if (outputFormat.equalsIgnoreCase("conll")) {
        if (parsedArguments.getBoolean("offsets")) {
          bwriter.write(annotator.tokenizeToCoNLL());
        } else {
          bwriter.write(annotator.tokenizeToCoNLLOffsets());
        }
      } else if (outputFormat.equalsIgnoreCase("oneline")) {
        bwriter.write(annotator.tokenizeToText());
      } else {
        final KAFDocument.LinguisticProcessor newLp = kaf
            .addLinguisticProcessor("text", "ixa-pipe-tok-" + lang, version
                + "-" + commit);
        newLp.setBeginTimestamp();
        annotator.tokenizeToKAF(kaf);
        newLp.setEndTimestamp();
        bwriter.write(kaf.toString());
      }
      breader.close();
    }
    bwriter.close();
  }
  
  
  public final void eval(final InputStream inputStream,
	      final OutputStream outputStream) throws IOException, JDOMException {
	    final String normalize = parsedArguments.getString("normalize");
	    final String lang = parsedArguments.getString("lang");
	    final String untokenizable = parsedArguments.getString("untokenizable");
	    final String segmentOnLinebreak = parsedArguments.getString("segmentOnLinebreak");
	    final String references = parsedArguments.getString("reference");
	    final String inputFormat = parsedArguments.getString("inputFormat");
	    final String verbose = parsedArguments.getString("verbose");
	    final Properties properties = setEvalProperties(lang, normalize, untokenizable, inputFormat, segmentOnLinebreak, references, verbose);
	    
	    
	    // Create Input Buffer for test file
	    BufferedReader breader = null;
	    breader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
	    
	    // Annotate, i.e. tokenise, test file
	    final Annotate annotator = new Annotate(breader, properties);
	    List<Token> testTokens = annotator.tokenizeToTokenList();
	    
	    List<Token> referenceTokens = null;
	    if (inputFormat.equals("tokenline")) {
	    	referenceTokens = getReferenceTokensTokenLine(references);
	    } else {
	    	referenceTokens = getReferenceTokensSentenceLine(references);
	    }
	    
	    TokenizerEvaluator evaluator = new TokenizerEvaluator();
	    evaluator.evaluate(referenceTokens, testTokens);
	    
	    System.out.println("Final F-Score: " + evaluator.getFMeasure());
  }
	  
  
  /**
   * Set up the TCP socket for annotation.
   */
  public final void server() {

    // load parameters into a properties
    final String port = parsedArguments.getString("port");
    final String lang = parsedArguments.getString("lang");
    final String normalize = parsedArguments.getString("normalize");
    final String untokenizable = parsedArguments.getString("untokenizable");
    final String kafversion = parsedArguments.getString("kafversion");
    final String inputkaf = String.valueOf(parsedArguments.getBoolean("inputkaf"));
    final String notok = String.valueOf(parsedArguments.getBoolean("notok"));
    final String segmentOnLinebreak = parsedArguments.getString("segmentOnLinebreak");
    final String verbose = parsedArguments.getString("verbose");
    final String offsets = String.valueOf(parsedArguments.getBoolean("offsets"));
    final String outputFormat = parsedArguments.getString("outputFormat");
    Properties serverProperties = setServerProperties(port, lang, normalize, untokenizable, kafversion, inputkaf, notok, outputFormat, offsets, segmentOnLinebreak, verbose);
    new RuleBasedTokenizerServer(serverProperties);
  }
  
  /**
   * The client to query the TCP server for annotation.
   * 
   * @param inputStream
   *          the stdin
   * @param outputStream
   *          stdout
   */
  public final void client(final InputStream inputStream,
      final OutputStream outputStream) {

    String host = parsedArguments.getString("host");
    String port = parsedArguments.getString("port");
    try (Socket socketClient = new Socket(host, Integer.parseInt(port));
        BufferedReader inFromUser = new BufferedReader(new InputStreamReader(
            System.in, "UTF-8"));
        BufferedWriter outToUser = new BufferedWriter(new OutputStreamWriter(
            System.out, "UTF-8"));
        BufferedWriter outToServer = new BufferedWriter(new OutputStreamWriter(
            socketClient.getOutputStream(), "UTF-8"));
        BufferedReader inFromServer = new BufferedReader(new InputStreamReader(
            socketClient.getInputStream(), "UTF-8"));) {

      // send data to server socket
      StringBuilder inText = new StringBuilder();
      String line;
      while ((line = inFromUser.readLine()) != null) {
        inText.append(line).append("\n");
      }
      inText.append("<ENDOFDOCUMENT>").append("\n");
      outToServer.write(inText.toString());
      outToServer.flush();
      
      // get data from server
      StringBuilder sb = new StringBuilder();
      String kafString;
      while ((kafString = inFromServer.readLine()) != null) {
        sb.append(kafString).append("\n");
      }
      outToUser.write(sb.toString());
    } catch (UnsupportedEncodingException e) {
      //this cannot happen but...
      throw new AssertionError("UTF-8 not supported");
    } catch (UnknownHostException e) {
      System.err.println("ERROR: Unknown hostname or IP address!");
      System.exit(1);
    } catch (NumberFormatException e) {
      System.err.println("Port number not correct!");
      System.exit(1);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  private void loadAnnotateParameters() {
    // specify language (for language dependent treatment of apostrophes)
    annotateParser
        .addArgument("-l", "--lang")
        .choices("de", "en", "es", "eu", "fr", "gl", "it", "nl", "pl")
        .required(true)
        .help(
            "It is REQUIRED to choose a language to perform annotation with ixa-pipe-tok.\n");
    annotateParser
        .addArgument("-n", "--normalize")
        .choices("alpino", "ancora", "ctag", "default", "ptb", "tiger",
            "tutpenn")
        .required(false)
        .setDefault("default")
        .help(
            "Set normalization method according to corpus; the default option does not escape "
                + "brackets or forward slashes. See README for more details.\n");
    annotateParser
        .addArgument("-u","--untokenizable")
        .choices("yes", "no")
        .setDefault("no")
        .required(false)
        .help("Print untokenizable characters.\n");
    annotateParser
        .addArgument("-o", "--outputFormat")
        .choices("conll", "oneline", "naf")
        .setDefault("naf")
        .required(false)
        .help(
            "Choose output format; it defaults to NAF.\n");
    annotateParser
        .addArgument("--offsets")
        .action(Arguments.storeFalse())
        .help(
            "Do not print offset and lenght information of tokens in CoNLL format.\n");
    annotateParser
        .addArgument("--inputkaf")
        .action(Arguments.storeTrue())
        .help(
            "Use this option if input is a KAF/NAF document with <raw> layer.\n");
    annotateParser
        .addArgument("--notok")
        .action(Arguments.storeTrue())
        .help(
            "Build a KAF document from an already tokenized sentence per line file.\n");
    annotateParser
        .addArgument("--segmentOnLinebreak")
        .choices("no", "single", "double")
        .setDefault("no")
        .required(false)
        .help("Segment boundary on each linebreak, even if there is no final punctuation mark (e.g. headings)\n");
    annotateParser
        .addArgument("-v","--verbose")
        .choices("yes", "no")
        .setDefault("yes")
        .required(false)
        .help("Surpress verbose progress output.\n");
    annotateParser.addArgument("--kafversion")
         .setDefault("v1.naf")
        .help("Set kaf document version.\n");
  }
  
  private void loadEvalParameters() {
	    // specify language (for language dependent treatment of apostrophes)
	    evalParser
	        .addArgument("-l", "--lang")
	        .choices("de", "en", "es", "eu", "fr", "gl", "it", "nl", "pl")
	        .required(true)
	        .help(
	            "It is REQUIRED to choose a language to perform annotation with ixa-pipe-tok.\n");
	    evalParser
        	.addArgument("-r", "--reference")
        	.required(true)
        	.help(
        		"It is REQUIRED to provide a reference tokenisation file in oneline format, with respect to which the output will be evaluated.\n");
	    evalParser
        	.addArgument("-i", "--inputFormat")
        	.choices("sentenceline", "tokenline")
        	.setDefault("tokenline")
        	.required(true)
        	.help(
            "Choose input data format; it defaults to tokenline (1 token per line).\n");
	    evalParser
	        .addArgument("-n", "--normalize")
	        .choices("alpino", "ancora", "ctag", "default", "ptb", "tiger",
	            "tutpenn")
	        .required(false)
	        .setDefault("default")
	        .help(
	            "Set normalization method according to corpus; the default option does not escape "
	                + "brackets or forward slashes. See README for more details.\n");
	    evalParser
	        .addArgument("-u","--untokenizable")
	        .choices("yes", "no")
	        .setDefault("no")
	        .required(false)
	        .help("Print untokenizable characters.\n");
	    evalParser
	        .addArgument("--segmentOnLinebreak")
	        .choices("no", "single", "double")
	        .setDefault("no")
	        .required(false)
	        .help("Segment boundary on each linebreak, even if there is no final punctuation mark (e.g. headings)\n");
	    evalParser
	    	.addArgument("-v","--verbose")
	    	.choices("yes", "no")
	    	.setDefault("yes")
	    	.required(false)
	    	.help("Surpress verbose progress output.\n");
	  }
  
  
  /**
   * Create the available parameters for NER tagging.
   */
  private void loadServerParameters() {
    
    serverParser.addArgument("-p", "--port")
        .required(true)
        .help("Port to be assigned to the server.\n");
    // specify language (for language dependent treatment of apostrophes)
    serverParser
        .addArgument("-l", "--lang")
        .choices("de", "en", "es", "eu", "fr", "gl", "it", "nl", "pl")
        .required(true)
        .help(
            "It is REQUIRED to choose a language to perform annotation with ixa-pipe-tok.\n");
    serverParser
        .addArgument("-n", "--normalize")
        .choices("alpino", "ancora", "ctag", "default", "ptb", "tiger",
            "tutpenn")
        .required(false)
        .setDefault("default")
        .help(
            "Set normalization method according to corpus; the default option does not escape "
                + "brackets or forward slashes. See README for more details.\n");
    serverParser
        .addArgument("-u","--untokenizable")
        .choices("yes", "no")
        .setDefault("no")
        .required(false)
        .help("Print untokenizable characters.\n");
    serverParser
        .addArgument("-o", "--outputFormat")
        .choices("conll", "oneline", "naf")
        .setDefault("naf")
        .required(false)
        .help(
            "Choose output format; it defaults to NAF.\n");
    serverParser
        .addArgument("--offsets")
        .action(Arguments.storeFalse())
        .help(
            "Do not print offset and lenght information of tokens in CoNLL format.\n");
    serverParser
        .addArgument("--inputkaf")
        .action(Arguments.storeTrue())
        .help(
            "Use this option if input is a KAF/NAF document with <raw> layer.\n");
    serverParser
        .addArgument("--notok")
        .action(Arguments.storeTrue())
        .help(
            "Build a KAF document from an already tokenized sentence per line file.\n");
    serverParser
        .addArgument("--segmentOnLinebreak")
        .choices("no", "single", "double")
        .setDefault("no")
        .required(false)
        .help("Segment boundary on each linebreak, even if there is no final punctuation mark (e.g. headings).\n");
    serverParser
    	.addArgument("-v","--verbose")
    	.choices("yes", "no")
    	.setDefault("yes")
    	.required(false)
    	.help("Surpress verbose progress output.\n");
    serverParser.addArgument("--kafversion")
         .setDefault("v1.naf")
        .help("Set kaf document version.\n");
  }
  
  private void loadClientParameters() {
    
    clientParser.addArgument("-p", "--port")
        .required(true)
        .help("Port of the TCP server.\n");
    clientParser.addArgument("--host")
        .required(false)
        .setDefault("localhost")
        .help("Hostname or IP where the TCP server is running.\n");
  }

  private Properties setAnnotateProperties(final String lang, final String normalize, final String untokenizable, final String segmentOnLinebreak, final String verbose) {
    final Properties annotateProperties = new Properties();
    annotateProperties.setProperty("language", lang);
    annotateProperties.setProperty("normalize", normalize);
    annotateProperties.setProperty("untokenizable", untokenizable);
    annotateProperties.setProperty("segmentOnLinebreak", segmentOnLinebreak);
    annotateProperties.setProperty("verbose", verbose);
    return annotateProperties;
  }
  
  private Properties setEvalProperties(final String lang, final String normalize, final String untokenizable, final String inputFormat, final String segmentOnLinebreak, final String reference, final String verbose) {
	    final Properties evalProperties = new Properties();
	    evalProperties.setProperty("language", lang);
	    evalProperties.setProperty("normalize", normalize);
	    evalProperties.setProperty("reference", reference);
	    evalProperties.setProperty("untokenizable", untokenizable);
	    evalProperties.setProperty("inputFormat", inputFormat);
	    evalProperties.setProperty("segmentOnLinebreak", segmentOnLinebreak);
	    evalProperties.setProperty("verbose", verbose);
	    return evalProperties;
	  }
    
    private Properties setServerProperties(final String port, final String lang, final String normalize, final String untokenizable, final String kafversion, final String inputkaf, final String notok, final String outputFormat, final String offsets, final String segmentOnLinebreak, final String verbose) {
      final Properties serverProperties = new Properties();
      serverProperties.setProperty("port", port);
      serverProperties.setProperty("language", lang);
      serverProperties.setProperty("normalize", normalize);
      serverProperties.setProperty("untokenizable", untokenizable);
      serverProperties.setProperty("kafversion", kafversion);
      serverProperties.setProperty("inputkaf", inputkaf);
      serverProperties.setProperty("notok", notok);
      serverProperties.setProperty("outputFormat", outputFormat);
      serverProperties.setProperty("offsets", offsets);
      serverProperties.setProperty("segmentOnLinebreak", segmentOnLinebreak);
      serverProperties.setProperty("verbose", verbose);
      return serverProperties;
  }
     /**
      * Get list of reference tokens by loading it from the file specified in the CLI parameter --reference
      */
    public List<Token> getReferenceTokensSentenceLine (String in) throws IOException { //List<Token>
       List<Token> referenceTokenList= new ArrayList<Token>();
  	   InputStream referenceInputStream = new FileInputStream(in);
  	   BufferedReader br = new BufferedReader(new InputStreamReader(referenceInputStream, "UTF-8"));
  	   String line;
  	   while ((line = br.readLine()) != null) {
  		  String[] lineArray = line.split(" ");
  		  for (String tok : lineArray) {
  			  referenceTokenList.add(new Token(tok));
  		  }
  	    }
  	   br.close();
  	   return referenceTokenList;
  	  }
    
    /**
     * Get list of reference tokens by loading it from the file specified in the CLI parameter --reference
     */
   public List<Token> getReferenceTokensTokenLine (String in) throws IOException { //List<Token>
      List<Token> referenceTokenList= new ArrayList<Token>();
 	   InputStream referenceInputStream = new FileInputStream(in);
 	   BufferedReader br = new BufferedReader(new InputStreamReader(referenceInputStream, "UTF-8"));
 	   String line;
 	   while ((line = br.readLine()) != null) {
 		  line = line.trim();
 		  if (line.length() > 0) {
 			  referenceTokenList.add(new Token(line));
 		  }
 		}
 	   br.close();
 	   return referenceTokenList;
 	  }

   /*
   String line;
   try {
     while ((line = breader.readLine()) != null) {
       line = line.trim();
       if (!line.startsWith("#")) {
         nonBreakerList.add(line);
       }
     }
   } catch (final IOException e) {
     e.printStackTrace();
   }
   */
   
}
