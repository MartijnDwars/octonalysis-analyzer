import org.metaborg.core.language.ILanguageImpl
import org.metaborg.spoofax.core.unit.ISpoofaxParseUnit
import org.spoofax.jsglr.client.imploder.{IToken, ImploderAttachment}

import scala.collection.immutable.IndexedSeq

class Tokenizer {
  /**
    * Tokenize the parse result (based on https://goo.gl/PW2Dzr).
    *
    * @param result
    */
  def tokenizeAll(result: ISpoofaxParseUnit, languageImpl: ILanguageImpl): IndexedSeq[IToken] = {
    val rootImploderAttachment = ImploderAttachment.get(result.ast())
    val tokenizer = rootImploderAttachment.getLeftToken.getTokenizer

    (0 until tokenizer.getTokenCount).map(tokenizer.getTokenAt)
  }
}
