/*******************************************************************************
 * Copyright (c) 2010-2019 Haifeng Li
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 *******************************************************************************/

package smile.neighbor;

import org.junit.Before;
import org.junit.Test;
import smile.math.distance.HammingDistance;
import smile.sort.HeapSelect;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.stream.Stream;
import static smile.neighbor.SNLSH.simhash64;

/**
 * Test data set: http://research.microsoft.com/en-us/downloads/607d14d9-20cd-47e3-85bc-a2f65cd28042/
 * 
 * @author Qiyang Zuo
 * @since 03/31/15
 */
public class SNLSHTest {

    private class Sentence extends SNLSH.AbstractSentence {
        public Sentence(String line) {
            this.line = line;
            this.tokens = tokenize(line);
        }

        @Override
        List<String> tokenize(String line) {
            return tokenize(line, " ");
        }

        private List<String> tokenize(String line, String regex) {
            List<String> tokens = new LinkedList<>();
            if (line == null || line.isEmpty()) {
                throw new IllegalArgumentException("Line should not be blank!");
            }
            String[] ss = line.split(regex);
            for (String s : ss) {
                if (s == null || s.isEmpty()) {
                    continue;
                }
                tokens.add(s);
            }
            return tokens;
        }
    }

    private String[] texts = {
            "This is a test case",
            "This is another test case",
            "This is another test case too",
            "I want to be far from other cases"
    };

    private List<Sentence> testData;
    private List<Sentence> trainData;
    private List<Sentence> toyData;
    private Map<String, Long> signCache; //tokens<->sign

    @Before
    public void before() throws IOException {
        trainData = loadData("msrp/msr_paraphrase_train.txt");
        testData = loadData("msrp/msr_paraphrase_test.txt");
        signCache = new HashMap<>();
        for (Sentence sentence : trainData) {
            long sign = simhash64(sentence.tokens);
            signCache.put(sentence.line, sign);
        }
        toyData = new ArrayList<>();
        for (String text : texts) {
            toyData.add(new Sentence(text));
        }
    }

    private List<Sentence> loadData(String path) throws IOException {
        List<Sentence> data = new ArrayList<>();
        Stream<String> lines = Files.lines(smile.util.Paths.getTestData(path));
        lines.forEach(line -> {
            List<String> s = tokenize(line, "\t");
            data.add(new Sentence(s.get(s.size() - 1)));
            data.add(new Sentence(s.get(s.size() - 2)));
        });
        return data.subList(2, data.size());
    }

    private Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] linearKNN(SNLSH.AbstractSentence q, int k) {
        @SuppressWarnings("unchecked")
        Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] neighbors = (Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[])Array.newInstance(Neighbor.class, k);
        HeapSelect<Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>> heap = new HeapSelect<>(neighbors);
        Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> neighbor = new Neighbor<>(null, null, 0, Double.MAX_VALUE);
        for (int i = 0; i < k; i++) {
            heap.add(neighbor);
        }
        long sign1 = simhash64(q.tokens);
        int hit = 0;
        for (Sentence sentence : trainData) {
            if(sentence.line.equals(q.line)) {
                continue;
            }
            long sign2 = signCache.get(sentence.line);
            double distance = HammingDistance.d(sign1, sign2);
            if(distance < heap.peek().distance) {
                heap.add(new Neighbor<>(sentence, sentence, 0, distance));
                hit++;
            }
        }
        heap.sort();
        if (hit < k) {
            @SuppressWarnings("unchecked")
            Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] n2 = (Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[])Array.newInstance(Neighbor.class, hit);
            int start = k - hit;
            for (int i = 0; i < hit; i++) {
                n2[i] = neighbors[i + start];
            }
            neighbors = n2;
        }

        return neighbors;
    }

    private Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> linearNearest(SNLSH.AbstractSentence q) {
        long sign1 = simhash64(q.tokens);
        double minDist = Double.MAX_VALUE;
        Sentence minKey = null;
        for (Sentence sentence : trainData) {
            if (sentence.line.equals(q.line)) {
                continue;
            }
            long sign2 = signCache.get(sentence.line);
            double distance = HammingDistance.d(sign1, sign2);
            if (distance < minDist) {
                minDist = distance;
                minKey = sentence;
            }
        }
        return new Neighbor<>(minKey, minKey, 0, minDist);
    }

    private void linearRange(Sentence q, double d, List<Neighbor<SNLSH.AbstractSentence,SNLSH.AbstractSentence>> neighbors) {
        long sign1 = simhash64(q.tokens);
        for (Sentence sentence : trainData) {
            if (sentence.line.equals(q.line)) {
                continue;
            }
            long sign2 = signCache.get(sentence.line);
            double distance = HammingDistance.d(sign1, sign2);
            if (distance <= d) {
                neighbors.add(new Neighbor<>(sentence, sentence, 0, distance));
            }
        }
    }

    @Test
    public void testKNN() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(toyData);
        SNLSH.AbstractSentence sentence = new Sentence(texts[0]);
        Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] ns = lsh.knn(sentence, 10);

        System.out.println("-----test knn: ------");
        for (int i = 0; i < ns.length; i++) {
            System.out.println("neighbor" + i + " : " + ns[i].key.line + ". distance: " + ns[i].distance);
        }
        System.out.println("------test knn end------");
    }

    @Test
    public void testKNNRecall() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(trainData);
        double recall = 0.0;
        for (SNLSH.AbstractSentence q : testData) {
            int k = 3;
            Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] n1 = lsh.knn(q, k);
            Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>[] n2 = linearKNN(q, k);
            int hit = 0;
            for (int m = 0; m < n1.length && n1[m] != null; m++) {
                for (int n = 0; n < n2.length && n2[n] != null; n++) {
                    if (n1[m].value.equals(n2[n].value)) {
                        hit++;
                        break;
                    }
                }
            }
            recall += 1.0 * hit / k;
        }
        recall /= testData.size();
        System.out.println("SNLSH KNN recall is " + recall);
    }

    @Test
    public void testNearest() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(toyData);
        System.out.println("----------test nearest start:-------");
        Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> n = lsh.nearest((SNLSH.AbstractSentence)new Sentence(texts[0]));
        System.out.println("neighbor" + " : " + n.key.line + " distance: " + n.distance);
        System.out.println("----------test nearest end-------");
    }

    @Test
    public void testNearestRecall() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(trainData);
        double recall = 0.0;
        for (SNLSH.AbstractSentence q : testData) {
            Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> n1 = lsh.nearest(q);
            Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> n2 = linearNearest(q);
            if (n1.value.equals(n2.value)) {
                recall++;
            }
        }
        recall /= testData.size();
        System.out.println("SNLSH Nearest recall is " + recall);
    }

    @Test
    public void testRange() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(toyData);
        List<Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>> ns = new ArrayList<>();
        lsh.range(new Sentence(texts[0]), 10, ns);
        System.out.println("-------test range begin-------");
        for (Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence> n : ns) {
            System.out.println(n.key.line + "  distance: " + n.distance);
        }
        System.out.println("-----test range end ----------");
    }


    @Test
    public void testRangeRecall() {
        SNLSH<SNLSH.AbstractSentence> lsh = createLSH(trainData);
        double dist = 15.0;
        double recall = 0.0;
        for (Sentence q : testData) {
            List<Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>> n1 = new ArrayList<>();
            lsh.range(q, dist, n1);
            List<Neighbor<SNLSH.AbstractSentence, SNLSH.AbstractSentence>> n2 = new ArrayList<>();
            linearRange(q, dist, n2);
            int hit = 0;
            for (int m = 0; m < n1.size(); m++) {
                for (int n = 0; n < n2.size(); n++) {
                    if (n1.get(m).value.equals(n2.get(n).value)) {
                        hit++;
                        break;
                    }
                }
            }
            if (!n2.isEmpty()) {
                recall += 1.0 * hit / n2.size();
            }
        }
        recall /= testData.size();
        System.out.println("SNLSH range recall is " + recall);
    }

    private SNLSH<SNLSH.AbstractSentence> createLSH(List<Sentence> data) {
        SNLSH<SNLSH.AbstractSentence> lsh = new SNLSH<>(8);
        for (Sentence sentence : data) {
            lsh.put(sentence, sentence);
        }
        return lsh;
    }

    private List<String> tokenize(String line, String regex) {
        List<String> tokens = new LinkedList<>();
        if (line == null || line.isEmpty()) {
            throw new IllegalArgumentException("Line should not be blank!");
        }
        String[] ss = line.split(regex);
        for (String s : ss) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            tokens.add(s);
        }
        return tokens;
    }
}
