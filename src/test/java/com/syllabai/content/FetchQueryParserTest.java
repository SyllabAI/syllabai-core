package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fetch query grammar (R4, plan §7): the deterministic parse every Fetch
 * resolution starts from. Cases mirror the golden-subset query shapes plus
 * the plan's own examples — series aliases, full/bare paper references,
 * qnum + part letters, MS/QP intent hints, and the honest empty parse.
 */
class FetchQueryParserTest {

    @Test
    @DisplayName("series words canonicalize: summer/june → JUN, january/jan → JAN, november/october → NOV")
    void seriesAliases() {
        assertThat(FetchQueryParser.parse("give me the answer to q4 from summer 2021").series())
                .isEqualTo("JUN");
        assertThat(FetchQueryParser.parse("question 6 june 2011 mark scheme").series())
                .isEqualTo("JUN");
        assertThat(FetchQueryParser.parse("mark scheme for q6 jan 2021 paper 4CH1/2C").series())
                .isEqualTo("JAN");
        assertThat(FetchQueryParser.parse("mark scheme for q2 november 2021 paper 4CH1/2C").series())
                .isEqualTo("NOV");
        assertThat(FetchQueryParser.parse("question 3 october 2021 answer").series())
                .isEqualTo("NOV");
    }

    @Test
    @DisplayName("full paper codes parse with slash, dash and inner spaces; normalize to CODE/UNIT")
    void paperCodes() {
        assertThat(FetchQueryParser.parse("what did question 2 ask in 4CH1/2CR 2022").paperCode())
                .isEqualTo("4CH1/2CR");
        assertThat(FetchQueryParser.parse("4ch0 - 1c q4 2011 answer").paperCode())
                .isEqualTo("4CH0/1C");
        assertThat(FetchQueryParser.parse("mark scheme for q2 november 2021 paper 4CH1/2C").paperCode())
                .isEqualTo("4CH1/2C");
        // the bare-unit path must not fire when a full code matched
        assertThat(FetchQueryParser.parse("4CH0/2C q5 2017 answer").unit()).isNull();
    }

    @Test
    @DisplayName("bare unit parses only as a standalone token")
    void bareUnit() {
        assertThat(FetchQueryParser.parse("mark scheme for Jan 2020 2C Q7b").unit()).isEqualTo("2C");
        assertThat(FetchQueryParser.parse("mark scheme for Jan 2020 2C Q7b").part()).isEqualTo("b");
        // digits inside a year are never a unit
        assertThat(FetchQueryParser.parse("question 6 summer 2020 mark scheme").unit()).isNull();
    }

    @Test
    @DisplayName("qnum + optional part letter")
    void questionNumbers() {
        ParsedFetchQuery q = FetchQueryParser.parse("give me the answer to q4 from summer 2021");
        assertThat(q.qnum()).isEqualTo(4);
        assertThat(q.part()).isNull();
        assertThat(FetchQueryParser.parse("mark scheme for Jan 2020 2C Q7b").qnum()).isEqualTo(7);
        assertThat(FetchQueryParser.parse("what did question 12 ask in 2021").qnum()).isEqualTo(12);
        // 'q10' must not parse as question 1 followed by garbage
        assertThat(FetchQueryParser.parse("q10 2019 answer").qnum()).isEqualTo(10);
    }

    @Test
    @DisplayName("intent hints: answer/mark scheme/solution ⇒ MS-seeking; what did/ask ⇒ QP-seeking")
    void intentHints() {
        assertThat(FetchQueryParser.parse("question 6 summer 2011 mark scheme").msSeeking()).isTrue();
        assertThat(FetchQueryParser.parse("give me the answer to q4 from summer 2021").msSeeking()).isTrue();
        assertThat(FetchQueryParser.parse("what did question 4 ask in 4CH1/2C 2017").msSeeking()).isFalse();
    }

    @Test
    @DisplayName("year anchors on the first 4-digit token in the 1900–2099 window")
    void years() {
        assertThat(FetchQueryParser.parse("question 6 jan 2016 mark scheme").year()).isEqualTo(2016);
        assertThat(FetchQueryParser.parse("4CH1/1C q7 2021 answer").year()).isEqualTo(2021);
    }

    @Test
    @DisplayName("no vocabulary → honest empty parse (caller logs a parse defect)")
    void emptyParse() {
        ParsedFetchQuery q = FetchQueryParser.parse("tell me about electrolysis");
        assertThat(q.isEmpty()).isTrue();
        assertThat(q.hasExplicitPaper()).isFalse();
        assertThat(FetchQueryParser.parse("").isEmpty()).isTrue();
        assertThat(FetchQueryParser.parse(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("plan §7 example end-to-end: 'mark scheme for Jan 2020 2C Q7b'")
    void planExample() {
        ParsedFetchQuery q = FetchQueryParser.parse("mark scheme for Jan 2020 2C Q7b");
        assertThat(q.paperCode()).isNull();
        assertThat(q.unit()).isEqualTo("2C");
        assertThat(q.series()).isEqualTo("JAN");
        assertThat(q.year()).isEqualTo(2020);
        assertThat(q.qnum()).isEqualTo(7);
        assertThat(q.part()).isEqualTo("b");
        assertThat(q.msSeeking()).isTrue();
        assertThat(q.hasExplicitPaper()).isTrue();
    }
}
