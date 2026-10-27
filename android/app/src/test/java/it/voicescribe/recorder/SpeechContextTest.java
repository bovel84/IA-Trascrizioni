package it.voicescribe.recorder;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.charset.StandardCharsets;
public class SpeechContextTest {
 @Test public void glossaryAndPrecedingWordsShareOneBoundedBudget(){String text=SpeechContext.prompt("SFIRS RAS Sardafactoring","Una frase molto lunga "+String.join(" ",java.util.Collections.nCopies(200,"precedente")));assertTrue(text.startsWith("SFIRS RAS Sardafactoring\n"));assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=220);assertTrue(text.endsWith("precedente"));}
 @Test public void emptyContextNeverAddsInstructionsOrInventedText(){assertEquals("",SpeechContext.prompt("", ""));assertEquals("SFIRS",SpeechContext.prompt("SFIRS",null));}
 @Test public void unicodeIsNeverCutInsideACodePoint(){String input=String.join("",java.util.Collections.nCopies(100,"é🙂"));String out=SpeechContext.glossary(input);assertTrue(out.getBytes(StandardCharsets.UTF_8).length<=160);assertFalse(Character.isHighSurrogate(out.charAt(out.length()-1)));assertFalse(out.contains("\ufffd"));}
 @Test public void precedingContextKeepsWholeWords(){assertEquals("due tre",SpeechContext.prompt("","due tre"));String out=SpeechContext.prompt("",String.join(" ",java.util.Collections.nCopies(100,"parola")));for(String word:out.split(" "))assertEquals("parola",word);}
 @Test public void glossaryCannotConsumeThePreviousTextBudget(){assertTrue(SpeechContext.prompt(String.join("",java.util.Collections.nCopies(300,"x")),"fine della frase").endsWith("fine della frase"));}
}
