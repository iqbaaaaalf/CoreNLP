package edu.stanford.nlp.pipeline;

import edu.stanford.nlp.ie.KBPRelationExtractor;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.time.TimeAnnotations;
import edu.stanford.nlp.time.Timex;
import edu.stanford.nlp.util.ArgumentParser;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.SystemUtils;
import edu.stanford.nlp.util.logging.Redwood;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * An annotator for entity linking to Wikipedia pages via the Wikidict.
 *
 * @author Gabor Angeli
 */
@SuppressWarnings("FieldCanBeLocal")
public class WikidictAnnotator extends SentenceAnnotator {

  /** A logger for this class */
  private static final Redwood.RedwoodChannels log = Redwood.channels(WikidictAnnotator.class);

  /** A pattern for simple numbers */
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9\\.]+");

  @ArgumentParser.Option(name="threads", gloss="The number of threads to run this annotator on")
  private int threads = 1;

  @ArgumentParser.Option(name="wikidict", gloss="The location of the <text, link, score> TSV file")
  private String wikidictPath = DefaultPaths.DEFAULT_WIKIDICT_TSV;

  @ArgumentParser.Option(name="threshold", gloss="The score threshold under which to discard links")
  private double threshold = 0.0;

  /**
   * The actual Wikidict dictionary.
   */
  private final Map<String, String> dictionary = new HashMap<>(21000000);  // it's gonna be large no matter what

  /**
   * Create a new WikiDict annotator, with the given name and properties.
   */
  public WikidictAnnotator(String name, Properties properties) {
    ArgumentParser.fillOptions(this, name, properties);
    long startTime = System.currentTimeMillis();
    log.info("Reading Wikidict from " + wikidictPath);
    try {
      int i = 0;
      String[] fields = new String[3];
      for (String line : IOUtils.readLines(wikidictPath, "UTF-8")) {
        if (line.charAt(0) == '\t') {
          continue;
        }
        StringUtils.splitOnChar(fields, line, '\t');
        if (i % 1000000 == 0) {
          log.info("Loaded " + i + " entries from Wikidict [" + SystemUtils.getMemoryInUse() + "MB memory used; " + Redwood.formatTimeDifference(System.currentTimeMillis() - startTime) + " elapsed]");
        }
        String surfaceForm = fields[0];
        String link = fields[1].intern();  // intern, as most entities have multiple surface forms
        // Check that the read entry is above the score threshold
        if (threshold > 0.0) {
          double score = Double.parseDouble(fields[2]);
          if (score < threshold) {
            continue;
          }
        }
        // Add the entry
        dictionary.put(surfaceForm, link);
        i += 1;
      }
      log.info("Done reading Wikidict (" + dictionary.size() + " links read; " + Redwood.formatTimeDifference(System.currentTimeMillis() - startTime) + " elapsed)");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** @see WikidictAnnotator#WikidictAnnotator(String, Properties) */
  @SuppressWarnings("unused")
  public WikidictAnnotator(Properties properties) {
    this(STANFORD_LINK, properties);

  }

  /**
   * Try to normalize timex values to the form they would appear in the knowledge base.
   * @param timex The timex value to normalize.
   * @return The normalized timex value (e.g., dates have the time of day removed, etc.)
   */
  public static String normalizeTimex(String timex) {
    if (timex.contains("T") && !"PRESENT".equals(timex)) {
      return timex.substring(0, timex.indexOf("T"));
    } else {
      return timex;
    }
  }


  /**
   * Link the given mention, if possible.
   *
   * @param mention The mention to link, as given by {@link EntityMentionsAnnotator}
   *
   * @return The Wikidict entry for the given mention, or the normalized timex / numeric value -- as appropriate.
   */
  public Optional<String> link(CoreMap mention) {
    String surfaceForm = mention.get(CoreAnnotations.OriginalTextAnnotation.class) == null ? mention.get(CoreAnnotations.TextAnnotation.class) : mention.get(CoreAnnotations.OriginalTextAnnotation.class);
    String ner = mention.get(CoreAnnotations.NamedEntityTagAnnotation.class);

    if (ner != null &&
        (KBPRelationExtractor.NERTag.DATE.name.equalsIgnoreCase(ner) ||
          "TIME".equalsIgnoreCase(ner) ||
          "SET".equalsIgnoreCase(ner)) &&
        mention.get(TimeAnnotations.TimexAnnotation.class) != null &&
        mention.get(TimeAnnotations.TimexAnnotation.class).value() != null) {
      // Case: normalize dates
      Timex timex = mention.get(TimeAnnotations.TimexAnnotation.class);
      if (timex.value() != null && !timex.value().equals("PRESENT") &&
          !timex.value().equals("PRESENT_REF") &&
          !timex.value().equals("PAST") &&
          !timex.value().equals("PAST_REF") &&
          !timex.value().equals("FUTURE") &&
          !timex.value().equals("FUTURE_REF")
        ) {
        return Optional.of(normalizeTimex(timex.value()));
      } else {
        return Optional.empty();
      }
    } else if (ner != null &&
        "ORDINAL".equalsIgnoreCase(ner) &&
        mention.get(CoreAnnotations.NumericValueAnnotation.class) != null) {
      // Case: normalize ordinals
      Number numericValue = mention.get(CoreAnnotations.NumericValueAnnotation.class);
      return Optional.of(numericValue.toString());
    } else if (NUMBER_PATTERN.matcher(surfaceForm).matches()) {
      // Case: keep numbers as is
      return Optional.of(surfaceForm);
    } else if (ner != null && !"O".equals(ner) && dictionary.containsKey(surfaceForm)) {
      // Case: link with Wikidict
      return Optional.of(dictionary.get(surfaceForm));
    } else {
      // Else: keep the surface form as is
      return Optional.empty();
    }
  }

  /** {@inheritDoc} */
  @Override
  protected int nThreads() {
    return threads;
  }

  /** {@inheritDoc} */
  @Override
  protected long maxTime() {
    return -1l;
  }

  /** {@inheritDoc} */
  @Override
  protected void doOneSentence(Annotation annotation, CoreMap sentence) {
    for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
      token.set(CoreAnnotations.WikipediaEntityAnnotation.class, "O");
    }

    for (CoreMap mention : sentence.get(CoreAnnotations.MentionsAnnotation.class)) {
      Optional<String> canonicalName = link(mention);
      if (canonicalName.isPresent()) {
        mention.set(CoreAnnotations.WikipediaEntityAnnotation.class, canonicalName.get());
        for (CoreLabel token : mention.get(CoreAnnotations.TokensAnnotation.class)) {
          token.set(CoreAnnotations.WikipediaEntityAnnotation.class, canonicalName.get());
        }
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  protected void doOneFailedSentence(Annotation annotation, CoreMap sentence) {
    /* do nothing */
  }

  /** {@inheritDoc} */
  @Override
  public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
    return Collections.singleton(CoreAnnotations.WikipediaEntityAnnotation.class);
  }

  /** {@inheritDoc} */
  @Override
  public Set<Class<? extends CoreAnnotation>> requires() {
    Set<Class<? extends CoreAnnotation>> requirements = new HashSet<>(Arrays.asList(
        CoreAnnotations.TextAnnotation.class,
        CoreAnnotations.TokensAnnotation.class,
        CoreAnnotations.SentencesAnnotation.class,
        CoreAnnotations.OriginalTextAnnotation.class,
        CoreAnnotations.MentionsAnnotation.class
    ));
    return Collections.unmodifiableSet(requirements);
  }


  /**
   * A debugging method to try entity linking sentences from the console.
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {
    Properties props = StringUtils.argsToProperties(args);
    props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner,entitymentions,entitylink");
    StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
    IOUtils.console("sentence> ", line -> {
      Annotation ann = new Annotation(line);
      pipeline.annotate(ann);
      List<CoreLabel> tokens = ann.get(CoreAnnotations.SentencesAnnotation.class).get(0).get(CoreAnnotations.TokensAnnotation.class);
      System.err.println(StringUtils.join(tokens.stream().map(x -> x.get(CoreAnnotations.WikipediaEntityAnnotation.class)), "  "));
    });
  }
}


