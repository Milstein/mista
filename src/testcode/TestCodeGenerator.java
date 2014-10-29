/* 	
	Author Dianxiang Xu
*/
package testcode;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;

import testgeneration.TransitionTree;
import testgeneration.TransitionTreeForThreatNet;
import testgeneration.TransitionTreeNode;


import kernel.CancellationException;
import kernel.Kernel;
import kernel.SystemOptions;

import mid.MID;
import mid.Mapping;
import mid.Marking;
import mid.Predicate;
import mid.GoalProperty;
import mid.Substitution;
import mid.Transition;
import mid.Tuple;

public abstract class TestCodeGenerator {
	
	protected boolean preferSpeed = false;
	
	protected static final String TestCodeMessage = "Test code generated by "+Kernel.SYSTEM_NAME;
	protected String newLine = "\n";
	protected String tab = "";
	
	protected TransitionTree transitionTree;
	protected MID mid;
	protected SystemOptions systemOptions;

	private final boolean assertEffect = false;
	private final boolean needNegation = true;

	public TestCodeGenerator(TransitionTree transitionTree) {
		this.transitionTree = transitionTree;
		this.mid = transitionTree.getMID();
		this.systemOptions = transitionTree.getSystemOptions();
	}
	
	public static TestCodeGenerator createCodeGenerator(TransitionTree transitionTree){

		assert transitionTree!=null;			
		assert transitionTree.getSystemOptions()!=null;			
		assert transitionTree.getSystemOptions().getLanguage()!=null;
		
		if (transitionTree.getSystemOptions().isOOLanguage()){ 
			if (transitionTree instanceof TransitionTreeForThreatNet || !transitionTree.getSystemOptions().createObjectReference())
				return new TestCodeGeneratorOOSystem(transitionTree);
			else
				return new TestCodeGeneratorOO(transitionTree);
		}
		else if (transitionTree.getSystemOptions().getLanguage()==TargetLanguage.HTML)
			return	new TestCodeGeneratorHTML(transitionTree);
		else if (transitionTree.getSystemOptions().getLanguage()==TargetLanguage.C)
			return	new TestCodeGeneratorC(transitionTree);
		else if (transitionTree.getSystemOptions().getLanguage()==TargetLanguage.KBT || 
				transitionTree.getSystemOptions().getLanguage()==TargetLanguage.RPC ||
				transitionTree.getSystemOptions().getLanguage()==TargetLanguage.SELENIUMDRIVER
				) 
			return	new TestCodeGeneratorKBT(transitionTree);
		else if (transitionTree.getSystemOptions().getLanguage()==TargetLanguage.UFT)
			return	new TestCodeGeneratorUFT(transitionTree);		
		return new TestCodeGeneratorHTML(transitionTree);		 
	}
	
	// ****************************************************************
	// test code generation
	// ****************************************************************
	public void saveTestSuiteCode(File testSuiteFile) throws CancellationException{
		newLine = "\n";
		if (systemOptions.isOOLanguage())
			newLine += ((TargetLanguageOO)systemOptions.getLanguage()).getIndentation();
		if (preferSpeed)
			generateAndSetTestCodeForTree();
		transitionTree.clearTraversedFlags(transitionTree.getRoot());
		ArrayList<TransitionTreeNode> allTests = transitionTree.getAllTestsForCodeGeneration();
		PrintWriter testSuiteWriter = null;
		try {
			testSuiteWriter = new PrintWriter(testSuiteFile);
		} catch (FileNotFoundException e) {
			if (Kernel.IS_DEBUGGING_MODE)
				e.printStackTrace();
			return;
		}
		if (systemOptions.generateSeparateTestFiles())
			saveTestsToSeparateFiles(allTests, testSuiteWriter, testSuiteFile);
		else
			saveTestsToSingleFile(allTests, testSuiteWriter);
		testSuiteWriter.close();
	}	
	
	abstract public void saveTestsToSingleFile(ArrayList<TransitionTreeNode> allTests, PrintWriter testSuiteWriter) throws CancellationException;
	abstract public void saveTestsToSeparateFiles(ArrayList<TransitionTreeNode> allTests, PrintWriter testSuiteWriter, File suiteFile) throws CancellationException;
	abstract public String generateSequenceCodeForReview(ArrayList<TransitionTreeNode> testSequence);
	
	// generate test code for nodes and node sequences
	protected void generateAndSetTestCodeForTree(){
		LinkedList<TransitionTreeNode> queue = new LinkedList<TransitionTreeNode>();
		for (TransitionTreeNode initNode: transitionTree.getRoot().children())
			queue.addLast(initNode);
		while (!queue.isEmpty()) {
			TransitionTreeNode node = queue.poll();
			if (!node.getEvent().equalsIgnoreCase(MID.ConstructorEvent)){
				node.setTestInputCode(generateTestInputCodeForNode(node));
				node.setTestOracleCode(generateTestOracleCodeForNode(node));
			}
			for (TransitionTreeNode child: node.children())
				queue.addLast(child);
		}
	}
	
	// test input part 
	protected String generateTestInputCodeForNode(TransitionTreeNode currentNode){
		assert !currentNode.getEvent().equalsIgnoreCase(MID.ConstructorEvent); 
		String testInputCode = "";
		if (!mid.isHidden(currentNode.getEvent())) {
			testInputCode += getOptionSetupCode(currentNode);
			testInputCode += getUserProvidedStatements(currentNode.getParaTable().getNonParaStrings());
			testInputCode += getInputActionCode(currentNode);
			// if dirty tests are expected to throw exceptions
			testInputCode = getExceptionHandlingCode(currentNode, testInputCode);
		}
		return testInputCode;
	} 

	protected String getUserProvidedStatements(ArrayList<String> statements) {
		String code = "";
		for (String statement : statements) {
			code += newLine + tab + statement;
		}
		return code;
	}
	
	// default for html/c
	// to be overridden for OO
	protected String getExceptionHandlingCode(TransitionTreeNode currentNode, String testInputCode){
		return testInputCode;
	}
	
	abstract protected String getInputActionCode(TransitionTreeNode currentNode);
	
	protected String getInputActionExpression(TransitionTreeNode currentNode) {
		String event = currentNode.getEvent();
		String inputActionCode = "";  
		if (currentNode.getParaTable().hasParameters()) { // 1st priority - implementation parameters entered via the GUI
			inputActionCode = getInputActionWithImplementationParameters(currentNode, currentNode.getParaTable().getParameters());
		} else if (mid.hasParameters(event)) {// 2nd priority - specification parameters in the mid file 
			inputActionCode = getInputActionWithSpecificationParameters(currentNode, mid.getParameters(event));
		} else if (systemOptions.generateTestParameters()){// 3rd priority - bindings from firings if expected
			inputActionCode = getInputActionFromFiring(currentNode);
		} else{// no parameters used  
			inputActionCode = getInputActionWithoutParameters(event);
		}
		if (systemOptions.isOOLanguage() && systemOptions.generateReferenceForMethodCall()){
			if (!(transitionTree instanceof TransitionTreeForThreatNet) && transitionTree.getSystemOptions().createObjectReference())
				inputActionCode = getInputActionObjectReference(inputActionCode);
		}
		return inputActionCode;
	}
	
	// parameters entered via GUI are implementation-level values 
	private String getInputActionWithImplementationParameters(TransitionTreeNode currentNode, ArrayList<String> implementationParameters){
		String event = currentNode.getEvent();
		Tuple tuple = new Tuple(implementationParameters);
		Mapping actionMapping = mid.getMethodWithVariables(event);
		if (actionMapping!=null){
			Substitution substitution = actionMapping.getPredicate().unify(tuple);
			if (substitution!=null) // number of parameters matches
				return substitution.substitute(actionMapping.getOperator());
			else if (systemOptions.getLanguage()==TargetLanguage.HTML)
				return actionMapping.getOperator();
			else 
				return getDefaultInputAction(actionMapping.getOperator(), implementationParameters);  
		}
		return getDefaultInputAction(event, implementationParameters);  
	}

	// specification or implementation-level parameters specified in the mid file  
	private String getInputActionWithSpecificationParameters(TransitionTreeNode currentNode, ArrayList<String> specificationParameters){
		ArrayList<String> implementationParameters = new ArrayList<String>();
		for (String parameter: specificationParameters){
			String object = mid.getObject(parameter);
			if (object==null)
				implementationParameters.add(parameter);
			else 
				implementationParameters.add(object); 
		}
		Mapping actionMapping = mid.getMethod(currentNode.getEvent(), new Tuple(implementationParameters));
		if (actionMapping!=null)  // event(actual parameters) has corresponding code
			return actionMapping.getOperator();
		else 
			return getInputActionWithImplementationParameters(currentNode, implementationParameters);
	}

	private String getInputActionWithoutParameters(String event) {
		Mapping method = mid.getMethod(event);
		String inputAction = (method != null)? method.getOperator().trim(): event;
		if (systemOptions.isOOLanguage()){
			if (!inputAction.endsWith(")") && !inputAction.endsWith(";") && !inputAction.endsWith("}"))
				inputAction += "()";
		}
		return inputAction;
	}
		
	protected String getInputActionObjectReference(String source){
		String objectReference = ((TargetLanguageOO)systemOptions.getLanguage()).getObjectReference(mid.getSystemName());
		return source.startsWith(objectReference)? source: objectReference+source;
	}
	
	// Transition arguments:
	// t: parameters are not specified by the transition- use all variables involved
	// t(): zero argument, meaning that the method invocation has no parameter
	// t(x1, x2, ...): replace xi with the bound value in the substitution 
	protected String getInputActionFromFiring(TransitionTreeNode currentNode){
		Substitution substitution = currentNode.getSubstitution();
		if (substitution!=null && substitution.hasBindings()){
			ArrayList<String> actualParameters = getActualParameters(currentNode);
			String event = currentNode.getEvent();
			if (mid.getMethod(event)==null) // action is not specified
				return getDefaultInputAction(event, actualParameters);
			else 
				return getInputActionWithSubstitution(event, new Tuple(actualParameters));
		}		
		else // no variable for the event
			return getInputActionWithoutParameters(currentNode.getEvent());
	}
	
	private String getInputActionWithSubstitution(String event, Tuple tuple){
		Mapping method = mid.getMethod(event, tuple);
		if (method!=null){
			return method.getOperator();
		}
		if (tuple.arity()>0){
			method = mid.getMethodWithVariables(event);
			if (method!=null){
				Substitution substitution = method.getPredicate().unify(tuple);
				if (substitution!=null) 
					return substitution.substitute(method.getOperator());
			}
		}
		return getDefaultInputAction(event, tuple.getArguments());
	}

	protected String getDefaultInputAction(String event, ArrayList<String> parameters) {
		String paraString = "";
		if (parameters.size()>0) {
			paraString = parameters.get(0);
			for (int i=1; i<parameters.size(); i++)
				paraString += ", " + parameters.get(i);
		}
		return event + "(" + paraString +")";
	}
	
	private ArrayList<String> getActualParameters(TransitionTreeNode currentNode) {
		Transition transition = currentNode.getTransition();
		Substitution substitution = currentNode.getSubstitution();
		ArrayList<String> actualParameters = new ArrayList<String>();
		ArrayList<String> formalParameters = transition.getArguments();
		if (formalParameters==null)
			formalParameters = transition.getAllVariables();
		for (String variable: formalParameters) {
			String value = substitution.getBinding(variable);
			String object = variable;
			if (value!=null) { 
				object = mid.getObject(value);
				if (object==null)
					object = value;
			}
			actualParameters.add(object); 
		}
		return actualParameters; 
	}
	
	protected ArrayList<TransitionTreeNode> getTestSequence(TransitionTreeNode leaf){
		TransitionTreeNode currentNode = leaf; 
		ArrayList<TransitionTreeNode> sequence = new ArrayList<TransitionTreeNode>();
		while (!currentNode.isRoot()) {
			sequence.add(0, currentNode);
			currentNode = currentNode.getParent();
		}
		return sequence;
	}

	protected String getTestId(ArrayList<TransitionTreeNode> testSequence) {
		return testSequence.get(testSequence.size()-1).getTestCaseId();
	}
	
	protected String getTestId(int testNo, ArrayList<TransitionTreeNode> testSequence) {
		return getTestId(testNo, testSequence.get(testSequence.size()-1));
	}

	protected String getTestId(int testNo, TransitionTreeNode leafNode) {
		return systemOptions.includeSeqIndicesInTestID()? testNo+"_"+leafNode.getTestCaseId(): testNo+"";
	}

	// test oracle part
	protected String generateTestOracleCodeForNode(TransitionTreeNode currentNode){
		assert !currentNode.getEvent().equalsIgnoreCase(MID.ConstructorEvent); 
		String testOracleCode = "";
		if (!mid.isHidden(currentNode.getEvent())) {
			if (currentNode.isNegative()) {	// dirty tests 
				if (systemOptions.verifyDirtyTestState())
					testOracleCode += getMarkingVerificationCode(currentNode);
			}
			else { // clean tests
				if (systemOptions.verifyPostconditions())
					testOracleCode += getConditionVerificationCode(currentNode, currentNode.getTransition().getPostcondition(), true, !needNegation);
				else if (systemOptions.verifyMarkings())
					testOracleCode += getMarkingVerificationCode(currentNode);
				if (systemOptions.isOOLanguage() && systemOptions.verifyNegatedConditions())
					testOracleCode += getConditionVerificationCode(currentNode, currentNode.getTransition().getDeletePrecondition(), true, needNegation);
				if (systemOptions.verifyEffects())
					testOracleCode += getConditionVerificationCode(currentNode, currentNode.getTransition().getEffect(), assertEffect, !needNegation);
				if (mid.getGoalProperties().size()>0 && systemOptions.createGoalTags() && systemOptions.hasTagCodeForTestFramework() && !systemOptions.areGoalTagsAtBeginningOfTests())
					testOracleCode += getGoalTagsInsideTest(currentNode);
			}
		}
		return testOracleCode;
	}

	private String getMarkingVerificationCode(TransitionTreeNode currentNode) {
		String code = "";
		Marking marking = currentNode.getMarking();
		for (String place: mid.getPlaces()) {
			if (!mid.isHidden(place) && marking.hasTuples(place)) {
				ArrayList<Tuple> tuples = marking.getTuples(place);
				for (Tuple tuple: tuples)
					code += newLine + getPredicateAccessorCode(currentNode.getTestCaseId(), place, tuple, true, !needNegation);
			}
		}
		return code;
	}
	
	// postcondition or effect
	// postcondition needs to  be converted to assert statements for OO language
	// effect does not need to. 
	private String getConditionVerificationCode(TransitionTreeNode currentNode, ArrayList<Predicate> conditions, boolean needAssertion, boolean needNegation) {
		String code = "";
		Substitution substitution =  currentNode.getSubstitution();
		if (conditions==null || substitution==null)
			return code;
		for (Predicate predicate: conditions) {
			String place = predicate.getName();
			if (!mid.isHidden(place)){
				Tuple tuple = substitution.substitute(predicate);
				code += newLine + getPredicateAccessorCode(currentNode.getTestCaseId(), place, tuple, needAssertion, needNegation);
			}	
		}
		return code;
	}

	// convert a single predicate in marking, postcondition, or effect  
	private String getPredicateAccessorCode(String testID, String place, Tuple tuple, boolean needAssertion, boolean needNegation){
		String accessorCode = "";  
		Mapping accessor = mid.getAccessor(place, tuple);
		if (accessor!=null)
			accessorCode = accessor.getOperator(); 
		else if (tuple.arity()>0){
			accessor = mid.getAccessorWithVariables(place);
			if (accessor!=null){
				Substitution substitution = accessor.getPredicate().unify(tuple);
				if (substitution!=null) {
					substitution = mid.substituteForObjects(substitution);
					accessorCode = substitution.substitute(accessor.getOperator());
				}
			}
		}
		// No accessor code found
		if (accessorCode.equals("")) {
//			accessorCode = tuple.arity()!=0? place+tuple: place+"()";
			accessorCode = getDefaultInputAction(place, tuple.getArguments());
		}
		
		if (systemOptions.getLanguage()==TargetLanguage.HTML)
			return tab + accessorCode;
		
		// for programming languages: OO, C 
		if (needAssertion)			// for postcondition
			return assertPredicate(testID, accessorCode, needNegation);
		else  						// for effect
			return normalizeEffectCode(accessorCode);
	}
		
	// default for html (negation is not effective)
	// overridden for OO
	protected String assertPredicate(String testID, String condition, boolean needNegation){
		return tab + condition;
	}
	
	protected String normalizeEffectCode(String accessorCode){
		return "";
	}
	
	// An option is represented as part of the precondition that needs to be set up in the implementation
	protected String getOptionSetupCode(TransitionTreeNode node) {
		String code = "";
		ArrayList<Predicate> precondition = node.getTransition().getPrecondition();
		if (precondition!=null) {	// the constructor's precondition is null
			for (Predicate input: precondition){
				String place = input.getName();
				if (mid.isOption(place)) {
					Tuple tuple = node.getSubstitution().substitute(input);
					code += newLine + tab + getInitTupleCode(place, tuple);
				}
			}
		}
		return code;
	}
	
	protected String getInitTupleCode(String place, Tuple tuple){
		String code = "";
		Mapping mutator = mid.getMutator(place, tuple);
		if (mutator!=null)
			code = mutator.getOperator(); 
		else if (tuple.arity()>0){
			mutator = mid.getMutatorWithVariables(place);
			if (mutator!=null){
				Substitution substitution = mutator.getPredicate().unify(tuple);
				if (substitution!=null) {
					substitution = mid.substituteForObjects(substitution);
					code = substitution.substitute(mutator.getOperator());
				}
			}
		}
		if (!code.equals(""))
			return normalizeSetupCode(code);
		else
			return code;
	}

	// default for html
	// to be overridden for OO
	protected String normalizeSetupCode(String code) {
		return code;
	}

	protected String getGoalTagsAtBeginningOfTest(ArrayList<TransitionTreeNode> testSequence){
		ArrayList<GoalProperty> unreachedGoals = new ArrayList<GoalProperty>();
		for (GoalProperty marking: mid.getGoalProperties())
			unreachedGoals.add(marking);
		for (TransitionTreeNode node: testSequence) {
			for (int i=unreachedGoals.size()-1; i>=0; i--){
				GoalProperty unreachedMarking = unreachedGoals.get(i);
				if (node.getMarking().isFirable(unreachedMarking))
					unreachedGoals.remove(unreachedMarking);
			}
			if (unreachedGoals.size()==0)
				break;
		}
		ArrayList<String> reachedGoalNames = new ArrayList<String>();
		for (GoalProperty marking: mid.getGoalProperties()){
			if (!unreachedGoals.contains(marking))
				reachedGoalNames.add(marking.getEvent());
		}
		return getGoalTagsCode(reachedGoalNames);
	}
	
	private String getGoalTagsInsideTest(TransitionTreeNode currentNode){
		ArrayList<String> reachedGoals = new ArrayList<String>();
		for (GoalProperty goalProperty: mid.getGoalProperties()){
			if (!goalProperty.getEvent().equals(MID.DEFAULT_GOAL_TAG) && currentNode.getMarking().isFirable(goalProperty)){
				reachedGoals.add(goalProperty.getEvent());
			}
		}
		return getGoalTagsCode(reachedGoals);
	}
	
	private String getGoalTagsCode(ArrayList<String> reachedGoals){
		String tagCode = transitionTree.getSystemOptions().getTagCodeForTestFramework();
		if (!tagCode.equals("") && reachedGoals.size()>0) {
			tagCode ="\n"+tagCode;
			tagCode = tagCode.indexOf("[NAME]")>=0? 
					tagCode.replace("[NAME]", getGoalNameList(reachedGoals, ' ')): 
					tagCode.replace("[NAME,]", getGoalNameList(reachedGoals, ','));
			return tagCode.replace("\n",newLine+tab);
		}
		return "";
	}
	
	private String getGoalNameList(ArrayList<String> goalNames, char delimiter){
		StringBuffer buffer = new StringBuffer(goalNames.get(0));
		for (int i=1; i<goalNames.size(); i++){
			buffer.append(delimiter);
			buffer.append(" ");
			buffer.append(goalNames.get(i));
		}
		return buffer.toString();
	}
	
}
