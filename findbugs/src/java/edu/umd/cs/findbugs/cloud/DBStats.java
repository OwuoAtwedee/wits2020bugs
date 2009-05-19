/*
 * FindBugs - Find Bugs in Java programs
 * Copyright (C) 2003-2008 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.cloud;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import edu.umd.cs.findbugs.BugPattern;
import edu.umd.cs.findbugs.BugRanker;
import edu.umd.cs.findbugs.I18N;
import edu.umd.cs.findbugs.PluginLoader;
import edu.umd.cs.findbugs.util.MergeMap;
import edu.umd.cs.findbugs.util.Multiset;



/**
 * @author pwilliam
 */
public class DBStats {
	enum BUG_STATUS {ACCEPTED, ASSIGNED, FIXED, FIX_LATER, NEW, 
		VERIFIED, VERIFIER_ASSIGNED, WILL_NOT_FIX, DUPLICATE;
		public static int score(String name) {
			try {
				BUG_STATUS value = valueOf(name);
				return value.score();
			} catch (RuntimeException e) {
				return 0;
			}
		}

		public int score() {
			switch (this) {
			case NEW:
				return 0;

			case ACCEPTED:
			case DUPLICATE:
			case WILL_NOT_FIX:
				return 1;

			case ASSIGNED:
			case FIXED:
			case FIX_LATER:
			case VERIFIED:
			case VERIFIER_ASSIGNED:
				return 2;

			default:
				throw new IllegalStateException();
			}
		}
	}

	enum Rank { SCARIEST, SCARY, TROUBLING, OF_CONCERN, UNRANKED;
	  static Rank getRank(int rank) {
		  if (rank <= 4) return SCARIEST;
		  if (rank <= 9) return SCARY;
		  if (rank <= 14) return TROUBLING;
		  if (rank <= 20) return OF_CONCERN;
		  return UNRANKED;
	  }
	}
	
	static Timestamp bucketByHour(Timestamp t) {
		Timestamp result = new Timestamp(t.getTime());
		result.setSeconds(0);
		result.setMinutes(0);
		result.setNanos(0);
		return result;
	}
	
	
	static class TimeSeries<K, V extends Comparable<? super V>> implements Comparable<TimeSeries<K,V>>{
		final K k;
		final int keyHash;
		final V v;


        public TimeSeries(K k, V v) {
	        this.k = k;
	        this.keyHash = System.identityHashCode(k);
	        this.v = v;
        }
        
      	
       @Override
    public String toString() {
    	   return v + " " + k;
       }

		public int compareTo(TimeSeries<K, V> o) {
			if (o == this) return 0;
			int result = v.compareTo(o.v);
			if (result != 0)
				return result;
			if (keyHash < o.keyHash) 
				return -1;
			return 1;
        }
	}
	
	public static void main(String args[]) throws Exception {
		 Map<String,String> officeLocation = new HashMap<String,String>();
		
		URL u = PluginLoader.getCoreResource("offices.properties");
		if (u != null) {
			BufferedReader in = new BufferedReader(new InputStreamReader(u.openStream()));
			while(true) {
				String s = in.readLine();
				if (s == null) break;
				if (s.trim().length() == 0)
					continue;
				int x = s.indexOf(':');
				
				if (x == -1)
					continue;
				String office = s.substring(0,x);
				for(String person : s.substring(x+1).split(" ")) 
					officeLocation.put(person, office);

				
			}
			in.close();
		}
		I18N i18n = I18N.instance();
		
		
		DBCloud cloud = new DBCloud(null);
		cloud.initialize();
		Connection c = cloud.getConnection();
		
		Map<Integer,Rank> bugRank = new HashMap<Integer, Rank>();
		Map<String, Integer> detailedBugRank = new HashMap<String, Integer>();
		
		PreparedStatement ps = c.prepareStatement("SELECT id, hash, bugPattern, priority FROM findbugs_issue");
		ResultSet rs = ps.executeQuery();
		while (rs.next()) {
			int col = 1;
			 int id = rs.getInt(col++);
			 String hash = rs.getString(col++);
			String bugType = rs.getString(col++);
			int priority  = rs.getInt(col++);
			BugPattern bugPattern = i18n.lookupBugPattern(bugType);
			if (bugPattern != null) {
				int rank = BugRanker.findRank(bugPattern, priority);
				bugRank.put(id, Rank.getRank(rank));
				detailedBugRank.put(hash, rank);
			}
		}
		rs.close();
		ps.close();
		
		
		
		ps = c.prepareStatement("SELECT who,  jvmLoadTime, findbugsLoadTime, analysisLoadTime, initialSyncTime, timestamp, numIssues"
					+ " FROM findbugs_invocation");
		
		MergeMap.MinMap <String, Timestamp> firstUse = new MergeMap.MinMap<String,Timestamp>();
		MergeMap.MinMap <String, Timestamp> reviewers = new MergeMap.MinMap<String,Timestamp>();
		MergeMap.MinMap <String, Timestamp> uniqueReviews = new MergeMap.MinMap<String,Timestamp>();
		
		HashSet<String> participants = new HashSet<String>();
		Multiset<String> invocations = new Multiset<String>();
		Multiset<String> participantsPerOffice = new Multiset<String>(new TreeMap<String,Integer>());
		 rs = ps.executeQuery();
		int invocationCount = 0;
		long invocationTotal = 0;
		long loadTotal = 0;
		while (rs.next()) {
			int col = 1;
			String who = rs.getString(col++);
			int jvmLoad = rs.getInt(col++);
			int fbLoad = rs.getInt(col++);
			int analysisLoad = rs.getInt(col++);
			int dbSync = rs.getInt(col++);
			Timestamp when = rs.getTimestamp(col++);
			int numIssues = rs.getInt(col++);
			invocationCount++;
			invocationTotal += jvmLoad + fbLoad + analysisLoad + dbSync;
			loadTotal +=  fbLoad + analysisLoad + dbSync;
			firstUse.put(who, when);
			if (numIssues > 3000)
				invocations.add(who);
			if (participants.add(who)) {
				String office = officeLocation.get(who);
				if (office == null) 
					office = "unknown";
				participantsPerOffice.add(office);
			}
				
		}
		rs.close();
		ps.close();
		
		ps = c.prepareStatement("SELECT id, issueId, who, designation, timestamp FROM findbugs_evaluation ORDER BY timestamp DESC");
		rs = ps.executeQuery();
		
		Multiset<String> issueReviewedBy =  new Multiset<String>();
		
		Multiset<String> allIssues = new Multiset<String>();
		Multiset<String> scariestIssues = new Multiset<String>();
		Multiset<String> scaryIssues = new Multiset<String>();
		Multiset<String> troublingIssues = new Multiset<String>();
		Multiset<Integer> scoreForIssue = new Multiset<Integer>();
		Multiset<Integer> reviewsForIssue = new Multiset<Integer>();
		
		HashSet<String> issueReviews = new HashSet<String>();
		HashSet<Integer> missingRank = new HashSet<Integer>();
		
		while (rs.next()) {
			int col = 1;
			int id = rs.getInt(col++);
			int issueId = rs.getInt(col++);
			String who = rs.getString(col++);
			String designation = rs.getString(col++);
			UserDesignation d = UserDesignation.valueOf(designation);
			designation = getDesignationTitle(i18n, d);
			int score;
			switch (d) {

			case BAD_ANALYSIS:
				score = -3;
				break;
			case NOT_A_BUG:
			case OBSOLETE_CODE:
				score = -2;
				break;
			case MOSTLY_HARMLESS:
				score = -1;
				break;
			case SHOULD_FIX:
				score = 1;
				break;
			case MUST_FIX:
			case I_WILL_FIX:
				score = 2;
				break;
			default:
				score = 0;
				break;
			}
			scoreForIssue.add(issueId, score);
			reviewsForIssue.add(issueId);
			Timestamp when = rs.getTimestamp(col++);
			Rank rank = bugRank.get(issueId);
			reviewers.put(who, when);
			String issueReviewer = who+"-" + issueId;
			if (issueReviews.add(issueReviewer)) {
				uniqueReviews.put(issueReviewer, when);
				allIssues.add(designation);
				issueReviewedBy.add(who);
				if (rank != null)
					switch(rank) {
					case SCARIEST:
						scariestIssues.add(designation);
						break;
					case SCARY:
						scaryIssues.add(designation);
						break;
					case TROUBLING:
						troublingIssues.add(designation);
						break;
						
					}
				else {
					if (missingRank.add(id)) {
						System.out.println("No rank for " + id);
					}
						
				}
			}
				
		}
		rs.close();	
		ps.close();
		
		PrintWriter scaryBugs = new PrintWriter("bugReportsForScaryIssues.csv");
		scaryBugs.println("assignedTo,status,rank,postmortem");
		Multiset<String> bugStatus = new Multiset<String>();
		HashSet<String> bugsSeen = new HashSet<String>();
		Multiset<String> bugScore = new Multiset<String>();
		
		Multiset<String> bugsFiled = new Multiset<String>();
		ps = c.prepareStatement("SELECT bugReportId,hash,status, whoFiled,assignedTo, postmortem, timestamp FROM findbugs_bugreport ORDER BY timestamp DESC");
		rs = ps.executeQuery();
		while (rs.next()) {
			int col = 1;
			String  id = rs.getString(col++);
			String hash = rs.getString(col++);
			
			String status = rs.getString(col++);
			
			String who = rs.getString(col++);
			String assignedTo = rs.getString(col++);
			String postmortem = rs.getString(col++);
			
			Timestamp when = rs.getTimestamp(col++);
			if (!bugsSeen.add(id))
				continue;
			Integer rank = detailedBugRank.get(hash);
			if (rank == null) {
				System.out.println("Could not find hash " + hash + " for " +id);
			}
			if (assignedTo != null && !"NEW".equals(status) 
					&& (rank != null && rank <= 4 || postmortem != null)) {
				if (postmortem != null) 
				      scaryBugs.printf("%s,%s,%s,%d,POSTMORTEM\n", assignedTo, id, status, rank);
				else 
					scaryBugs.printf("%s,%s,%s,%d\n", assignedTo, id, status, rank);
			} 
			
			if (!id.equals(DBCloud.PENDING) && !id.equals(DBCloud.NONE)) {
				bugStatus.add(status);
				bugsFiled.add(who);
				bugScore.add(who, BUG_STATUS.score(status));
			}
		}
		
		rs.close();	
		ps.close();
		c.close();
		scaryBugs.close();
		Multiset<String> overallEvaluation = new Multiset<String>();
		for(Map.Entry<Integer,Integer> e :  scoreForIssue.entrySet()) {
			int value = e.getValue();
			int num = reviewsForIssue.getCount(e.getKey());
			if (num == 0)
				continue;
			int score = (int) Math.round(value / (double) num);
			// System.out.printf("%s %2d %2d\n", score, value, num);
			overallEvaluation.add(
					getDesignationTitle(i18n, getDesignationFromScore(score)));
		}

		
		
		System.out.printf("%6d invocations\n", invocationCount);
		System.out.printf("%6d invocations time (secs)\n", invocationTotal/invocationCount/1000);
		System.out.printf("%6d load time (secs)\n", loadTotal/invocationCount/1000);
		System.out.println();
		
			
		printTimeSeries("users.csv", "Unique users", firstUse);
		printTimeSeries("reviewers.csv", "Unique reviewers", reviewers);
		printTimeSeries("reviews.csv", "Total reviews", uniqueReviews);	
		
		
		PrintWriter out = new PrintWriter("bug_status.csv");
		out.println("Status,Number of bugs");
		printMultiset(out, "Bug status", bugStatus);
		out.close();
		
		
		out = new PrintWriter("reviews_by_category.csv");
		out.println("Category,Number of reviews");
		printMultisetContents(out, "", allIssues);
		out.close();
		
		out = new PrintWriter("overall_review_of_issue.csv");
		out.println("Category,Number of issues");
		printMultisetContents(out, "", overallEvaluation);
		out.close();
	
		
		out = new PrintWriter("reviews_by_rank_and_category.csv");
		out.println("Rank,Category,Number of reviews");
		printMultisetContents(out, "Scariest,", scariestIssues);
		printMultisetContents(out, "Scary,", scaryIssues);
		printMultisetContents(out, "Troubling,", troublingIssues);
		out.close();
		
		out = new PrintWriter("bugs_filed.csv");
		out.println("rank,bugs filed,who");
		DBCloud.printLeaderBoard2(out, bugsFiled, 100, null, "%s,%s,%s\n", "participants per office");
		out.close();
	
		out = new PrintWriter("bug_score.csv");
		out.println("rank,bug score,who");
		DBCloud.printLeaderBoard2(out, bugScore, 100, null, "%s,%s,%s\n", "participants per office");
		out.close();

		
		out = new PrintWriter("most_participants_by_office.csv");
		out.println("rank,participants,office");
		DBCloud.printLeaderBoard2(out, participantsPerOffice, 100, null, "%s,%s,%s\n", "participants per office");
		out.close();
	

		out = new PrintWriter("most_participants_by_office.csv");
		out.println("rank,participants,office");
		DBCloud.printLeaderBoard2(out, participantsPerOffice, 100, null, "%s,%s,%s\n", "participants per office");
		out.close();
		
		out = new PrintWriter("most_issues_reviewed_individual.csv");
		out.println("rank,reviews,reviewers");
		DBCloud.printLeaderBoard2(out, issueReviewedBy, 10000, null, "%s,%s,%s\n", "num issues reviewed");
		out.close();
	
	}

	/**
     * @param value
     */
    private static UserDesignation getDesignationFromScore(int value) {
	    if (value <= -3)
	    	return UserDesignation.BAD_ANALYSIS;
	    else switch(value) {
	    case -2:
	    	return  UserDesignation.NOT_A_BUG;
	    case -1:
	    	return UserDesignation.MOSTLY_HARMLESS;
	    case 0:
	    	return UserDesignation.NEEDS_STUDY;
	    case 1:
	    	return UserDesignation.SHOULD_FIX;
	    default:
	    	return UserDesignation.MUST_FIX;
	    }
    }

	/**
     * @param i18n
     * @param d
     * @return
     */
    private static String getDesignationTitle(I18N i18n, UserDesignation d) {
	    String designation;
	    switch (d) {
	    case OBSOLETE_CODE:
	    	designation= "obsolete code";
	    	break;
	    case MUST_FIX: 
	    	designation= "Must fix";
	    	break;
	    case SHOULD_FIX: 
	    	designation= "Should fix";
	    	break;
	    default:
	    	designation =  i18n.getUserDesignation(d.name());
	    }
	    return designation;
    }

	/**
     * @param out TODO
	 * @param allIssues
     */
    private static void printMultiset(PrintWriter out, String title, Multiset<String> allIssues) {
	    printMultisetContents(out, "", allIssues);
		
    }

	/**
     * @param allIssues
     */
    private static void printMultisetContents(PrintWriter out, String prefix, Multiset<String> allIssues) {
	    for(Map.Entry<String, Integer> e : allIssues.entrySet())
			out.printf("%s%s,%d\n", prefix, e.getKey(), e.getValue());
    }

    
    final static  Date fixitStart = new Date("May 11, 2009");
	
    
	private static void printTimeSeries(String filename, String title, MergeMap.MinMap<String, Timestamp> firstUse) throws FileNotFoundException {
		PrintWriter out = new PrintWriter(filename);
		out.println(title+",time,full time");
	    TreeSet<TimeSeries<String, Timestamp>> series = new TreeSet<TimeSeries<String, Timestamp>>();
		for(Map.Entry<String, Timestamp> e : firstUse.entrySet()) {
			series.add(new TimeSeries<String,Timestamp>(e.getKey(), e.getValue()));
		}
		
		Multiset<Timestamp> counter = new Multiset<Timestamp>(new TreeMap<Timestamp, Integer>());
		for(TimeSeries<String, Timestamp> t : series) {
			counter.add(bucketByHour(t.v));
		}
		int total = 0;
		SimpleDateFormat format = new SimpleDateFormat("h a EEE");
		SimpleDateFormat defaultFormat = new SimpleDateFormat();
		for(Map.Entry<Timestamp, Integer> e : counter.entrySet()) {
			Timestamp time = e.getKey();
			total += e.getValue();
			if (time.after(fixitStart))
			  out.printf("%d,%s,%s\n", total, format.format(time), defaultFormat.format(time));
		}
		out.close();
		
    }

}
