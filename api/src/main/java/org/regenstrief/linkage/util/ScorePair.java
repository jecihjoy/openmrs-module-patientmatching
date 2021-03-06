package org.regenstrief.linkage.util;

/**
 * Takes a record and calculates a score between them using the options in the
 * given MatchingConfig object
 *
 * TODO: Add size parameter to lds1_frequencies & lds2_frequencies
 * TODO: Implement functionality for these two parameters: 
 * - A flag indicating whether to use null tokens when scaling agreement weight based on term frequency (default-no)
 * - A flag indicating how to establish agreement among fields when one or both fields are null (eg, apply disagreement weight, apply agreement weight, or apply ze
 */
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.apache.commons.lang.StringUtils;

import org.openmrs.module.patientmatching.MatchingConstants;
import org.regenstrief.linkage.MatchResult;
import org.regenstrief.linkage.MatchVector;
import org.regenstrief.linkage.NullDemographicsMatchVector;
import org.regenstrief.linkage.Record;
import org.regenstrief.linkage.analysis.Modifier;
import org.regenstrief.linkage.analysis.VectorTable;

public class ScorePair {

	private VectorTable vt;
	private MatchingConfig mc;
	private List<Modifier> modifiers;
	private Hashtable<MatchVector, Long> observed_vectors;

	public ScorePair(MatchingConfig mc) {
		this.mc = mc;
		vt = new VectorTable(mc);
		modifiers = new ArrayList<Modifier>();
		observed_vectors = new Hashtable<MatchVector, Long>();
	}

	public void addScoreModifier(Modifier sm) {
		modifiers.add(sm);
	}

	/**
	 * Scores a pair of records
	 * 
	 * @param rec1 first record to score
	 * @param rec2 second record to score
	 * @return MatchResult of rec1 paired with rec2
	 * @should indicate a match on patients with multiple identifiers for an identifier type
	 */
	public MatchResult scorePair(Record rec1, Record rec2) {
		final MatchVector mv;
		if (rec1.hasNullValues() || rec2.hasNullValues()) {
			mv = new NullDemographicsMatchVector();
		} else {
			mv = new MatchVector();
		}

		for (final MatchingConfigRow mcr : mc.getIncludedColumns()) {
			final String comparison_demographic = mcr.getName();
			final int algorithm = mcr.getAlgorithm();
			final double threshold = mcr.getThreshold();

			String data1 = rec1.getDemographic(comparison_demographic);
			String data2 = rec2.getDemographic(comparison_demographic);

			if (StringUtils.isBlank(data1) || StringUtils.isBlank(data2)) {
				NullDemographicsMatchVector nsmv = (NullDemographicsMatchVector) mv;
				nsmv.hadNullValue(comparison_demographic);
			}

			// multi-field demographics need to be analyzed on each combination of values
			// TODO base this on a flag on MatchingConfigRow vs the beginning of the name
			if (comparison_demographic.startsWith("(Identifier)")) {
				for (final String[] candidate : getCandidatesFromMultiFieldDemographics(data1, data2)) {
					// TODO use something other than String[] or guarantee size == 2
					if (match(mv, comparison_demographic, algorithm, threshold, candidate[0], candidate[1])) {
						break;
					}
				}
			} else {
				match(mv, comparison_demographic, algorithm, threshold, data1, data2);
			}
		}

		enableInterchageableFieldComparsion(rec1, rec2, mv);
		MatchResult mr = new MatchResult(vt.getScore(mv), vt.getInclusiveScore(mv), vt.getMatchVectorTrueProbability(mv), vt.getMatchVectorFalseProbability(mv), vt.getSensitivity(mv), vt.getSpecificity(mv), mv, vt.getScoreVector(mv), rec1, rec2, mc);
		for (Modifier m : modifiers) {
			mr = m.getModifiedMatchResult(mr, mc);
		}

		mr.setCertainty(1);
		mr.setMatch_status(MatchResult.UNKNOWN);

		Long l = observed_vectors.get(mr.getMatchVector());
		if (l == null) {
			l = Long.valueOf(1);
		} else {
			l = Long.valueOf(l.longValue() + 1);
		}
		observed_vectors.put(mr.getMatchVector(), l);

		return mr;
	}

	/**
	 * returns a list of all possible combinations of candidates from multiple
	 * field demographics
	 *
	 * @param data1
	 * @param data2
	 * @return
	 * @should return a list of all possible permutations
	 */
	protected List<String[]> getCandidatesFromMultiFieldDemographics(String data1, String data2) {
		String[] a = data1.split(MatchingConstants.MULTI_FIELD_DELIMITER);
		String[] b = data2.split(MatchingConstants.MULTI_FIELD_DELIMITER);

		List<String[]> res = new ArrayList<String[]>();
		for (String i : a) {
			for (String j : b) {
				res.add(new String[]{i, j});
			}
		}

		return res;
	}

	private boolean match(MatchVector mv, String comparison_demographic, int algorithm, double threshold, String data1, String data2) {
		if (data1 == null || data2 == null) {
			mv.setMatch(comparison_demographic, 0, false);
			return false;
		}

		final float similarity;
		final boolean match;
		switch (algorithm) {

			case (MatchingConfig.EXACT_MATCH):
				match = StringMatch.exactMatch(data1, data2);
				similarity = match ? 1 : 0;
				break;

			case (MatchingConfig.JWC):
				similarity = StringMatch.getJWCMatchSimilarity(data1, data2);
				match = similarity > threshold;
				break;

			case (MatchingConfig.LCS):
				similarity = StringMatch.getLCSMatchSimilarity(data1, data2);
				match = similarity > threshold;
				break;

			case (MatchingConfig.LEV):
				similarity = StringMatch.getLEVMatchSimilarity(data1, data2);
				match = similarity > threshold;
				break;
				
			case (MatchingConfig.DICE):
				similarity = StringMatch.getDiceMatchSimilarity(data1, data2);
				match = similarity > threshold;
				break;

			default:
				throw new IllegalArgumentException("Unexpected algorithm: " + algorithm);
		}
		
		mv.setMatch(comparison_demographic, similarity, match);
		return match;
	}

	/**
	 * This method would check if the interchangeable columns have same value ,
	 * if the columns match with each other then the individual columns which
	 * make the concat1 column would be set as true
	 **/
	private void enableInterchageableFieldComparsion(Record rec1, Record rec2, final MatchVector mv) {
		for (final String comparision_demographic : mc.getInterchangeableColumns()) {
			String rec1ConcatValue = rec1.getDemographic(comparision_demographic);
			String rec2ConcatValue = rec2.getDemographic(comparision_demographic);
			if (StringUtils.isNotEmpty(rec1ConcatValue) || StringUtils.isNotEmpty(rec2ConcatValue)) {
				float similarity = StringMatch.getLCSMatchSimilarity(rec1ConcatValue, rec2ConcatValue);
				if (similarity > StringMatch.LCS_THRESH) {
					List<String> interchangeableColumns = mc.getConcatenatedDemographics(comparision_demographic);
					for (String mcr: interchangeableColumns) {
						mv.setMatch(mcr, similarity, true);
					}
				}
				return;
			}
		}

		return;
	}

	public Hashtable<MatchVector, Long> getObservedVectors() {
		return observed_vectors;
	}
}