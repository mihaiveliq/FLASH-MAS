/*******************************************************************************
 * Copyright (C) 2018 Andrei Olaru.
 * 
 * This file is part of Flash-MAS. The CONTRIBUTORS.md file lists people who have been previously involved with this project.
 * 
 * Flash-MAS is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or any later version.
 * 
 * Flash-MAS is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Flash-MAS.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package net.xqhs.flash.core;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.xqhs.flash.core.util.ContentHolder;
import net.xqhs.flash.core.util.MultiTreeMap;
import net.xqhs.util.XML.XMLParser;
import net.xqhs.util.XML.XMLTree;
import net.xqhs.util.XML.XMLTree.XMLNode;
import net.xqhs.util.XML.XMLTree.XMLNode.XMLAttribute;
import net.xqhs.util.logging.Logger;
import net.xqhs.util.logging.UnitComponentExt;

/**
 * This class manages deployment configurations. It handles loading the elements of the configuration from various
 * sources -- default values, arguments given to the program, or settings specified in the deployment file.
 * <p>
 * The precedence of values for settings is the following (latter values override former values):
 * <ul>
 * <li>values given in DEFAULTS member;
 * <li>values given in the deployment file.
 * <li>values given as command-line arguments;
 * </ul>
 * <p>
 * The configuration is created as an "entity list" which is a tree (this class itself extends {@link MultiTreeMap}), in
 * which the first level are entity types, and other each type entities are listed by name (if any).
 * 
 * @author Andrei Olaru
 */
public class DeploymentConfiguration extends MultiTreeMap
{
	/**
	 * The class UID.
	 */
	private static final long				serialVersionUID				= 5157567185843194635L;
	
	/**
	 * Prefix of category names used in CLI.
	 */
	public static final String				CLI_CATEGORY_PREFIX				= "-";
	/**
	 * Separator of parts of a name and of parameter and value.
	 */
	public static final String				NAME_SEPARATOR					= ":";
	/**
	 * Separator for elements in the load order setting.
	 */
	public static final String				LOAD_ORDER_SEPARATOR			= " ";
	/**
	 * The name of nodes containing parameters.
	 */
	public static final String				PARAMETER_ELEMENT_NAME			= "parameter";
	/**
	 * The name of the attribute which contains the kind.
	 */
	public static final String				KIND_ATTRIBUTE_NAME				= "kind";
	/**
	 * The name of the attribute of a parameter node holding the name of the parameter.
	 */
	public static final String				PARAMETER_NAME					= "name";
	/**
	 * The name of the attribute of a parameter node holding the value of the parameter.
	 */
	public static final String				PARAMETER_VALUE					= "value";
	/**
	 * The name of the attribute which contains the name.
	 */
	public static final String				NAME_ATTRIBUTE_NAME				= "name";
	/**
	 * The name of the element(s) which contain entity context.
	 */
	public static final String				CONTEXT_ELEMENT_NAME			= "in-context-of";
	/**
	 * Name of XML nodes for entities other than those in {@link CategoryName}.
	 */
	public static final String				GENERAL_ENTITY_NAME				= "entity";
	/**
	 * The name of the XML attribute specifying the type of the entity.
	 */
	public static final String				GENERAL_ENTITY_TYPE_ATTRIBUTE	= "type";
	
	/**
	 * The name of the (singleton) entry in the configuration tree, under which all entities are listed by their name or
	 * generated identifier.
	 * <p>
	 * This constant is also used as a key in entity nodes for their id (if they don't have an identifiable name.
	 */
	public static final String				NAME_LIST_ENTRY					= "#by-name";
	
	/**
	 * Root package for FLASH classes.
	 */
	public static final String				ROOT_PACKAGE					= "net.xqhs.flash";
	/**
	 * Package for core FLASH functionality
	 */
	public static final String				CORE_PACKAGE					= "core";
	/**
	 * The default directory for deployment files.
	 */
	public static final String				DEPLOYMENT_FILE_DIRECTORY		= "src-deployment/";
	
	/**
	 * Default values.
	 */
	public static final Map<String, String>	DEFAULTS						= new HashMap<>();
	
	/**
	 * A node in the context stack. The context stack is used in order to keep track of location in the configuration
	 * tree while parsing CLI arguments.
	 */
	static class CtxtTriple
	{
		/**
		 * The name of the current category.
		 */
		String			category;
		/**
		 * The subtree of the current category, will contain elements in this category.
		 */
		MultiTreeMap	catTree;
		/**
		 * The subtree of the current element, will contain parameters or subordinate categories.
		 */
		MultiTreeMap	elemTree;
		
		/**
		 * Constructor.
		 * 
		 * @param cat
		 *            - category name.
		 * @param categoryTree
		 *            - category tree (may be <code>null</code>).
		 * @param elTree
		 *            - current element tree (may be <code>null</code>).
		 */
		public CtxtTriple(String cat, MultiTreeMap categoryTree, MultiTreeMap elTree)
		{
			category = cat;
			catTree = categoryTree;
			elemTree = elTree;
		}
		
		@Override
		public String toString()
		{
			return "{" + category + "/" + (catTree != null ? catTree.toString(1, true) : "-") + "/"
					+ (elemTree != null ? elemTree.toString(1, true) : "-") + "}";
		}
	}
	
	/**
	 * The default configuration. Only single values can be added here at this time.
	 */
	static
	{
		DEFAULTS.put(CategoryName.SCHEMA.s(), "src-schema/deployment-schema.xsd");
		DEFAULTS.put(CategoryName.DEPLOYMENT_FILE.s(), DEPLOYMENT_FILE_DIRECTORY +
		
		// "ChatAgents/deployment-chatAgents.xml"
				"ComplexDeployment/deployment-complexDeployment.xml"
		// "scenario/examples/sclaim_tatami2/simpleScenarioE/scenarioE-tATAmI2-plus.xml"
		
		);
		DEFAULTS.put(CategoryName.LOAD_ORDER.s(), "support agent");
	}
	
	/**
	 * The method loads all available values from the specified sources.
	 * <p>
	 * The only given source is the arguments the program has received, as the name of the deployment file will be
	 * decided by this method. If it is instructed through the parameter, the deployment file is parsed, producing an
	 * additional source of configuration values.
	 * <p>
	 * The <code>load()</code> method can be called only once. It is why all sources must be given in a single call to
	 * <code>load()</code>.
	 * <p>
	 * Therefore, if it is desired to pick <i>any</i> settings from the deployment file, the <code>boolean</code>
	 * argument should be set to <code>true</code>.
	 * 
	 * @param programArguments
	 *            - the arguments passed to the application, exactly as they were passed.
	 * @param parseDeploymentFile
	 *            - if <code>true</code>, the deployment file will be parsed to obtain the setting values placed in the
	 *            deployment; also, the {@link XMLTree} instance resulting from the parsing will be placed as content in
	 *            the last parameter.
	 * @param loadedXML
	 *            - if the deployment file is parsed and this argument is not <code>null</code>, the resulting
	 *            {@link XMLTree} instance will be stored in this ContentHolder instance.
	 * @return the instance itself, which is also the {@link MultiTreeMap} that contains all settings.
	 * 
	 * @throws ConfigLockedException
	 *             - if load() is called more than once.
	 */
	public MultiTreeMap loadConfiguration(List<String> programArguments, boolean parseDeploymentFile,
			ContentHolder<XMLTree> loadedXML) throws ConfigLockedException
	{
		locked();
		UnitComponentExt log = (UnitComponentExt) new UnitComponentExt().setUnitName("settings load");
		
		MultiTreeMap deployment = this.addSingleTreeGet(CategoryName.DEPLOYMENT.s(), new MultiTreeMap());
		
		// ====================================== get default settings
		for(String setting : DEFAULTS.keySet())
			deployment.addSingleValue(setting, DEFAULTS.get(setting));
		log.lf("initial tree:", this);
		
		log.lf("program arguments:", programArguments);
		
		// ====================================== get deployment file and schema
		boolean scenarioFirst = false;
		if(programArguments.size() > 0 && programArguments.get(0).length() > 0
				&& !programArguments.get(0).startsWith(CLI_CATEGORY_PREFIX) && !programArguments.get(0).contains(":"))
		{
			deployment.setValue(CategoryName.DEPLOYMENT_FILE.s(), programArguments.get(0));
			scenarioFirst = true;
		}
		else
			for(Iterator<String> it = programArguments.iterator(); it.hasNext();)
			{
				String arg = it.next();
				if(isCategory(arg) && (getCategory(arg).equals(CategoryName.DEPLOYMENT_FILE.s())
						|| getCategory(arg).equals(CategoryName.SCHEMA.s())))
				{
					String val = null;
					if(it.hasNext() || isCategory(val = it.next()))
						throw new IllegalArgumentException(
								"Program argument after " + arg + " should be a correct value.");
					deployment.setValue(getCategory(arg), val);
				}
			}
		log.lf("loading scenario [] with schema [].", deployment.getSingleValue(CategoryName.DEPLOYMENT_FILE.s()),
				deployment.getSingleValue(CategoryName.SCHEMA.s()));
		
		// ====================================== context management
		Deque<CtxtTriple> context = null; // categories & elements context
		// do not create a base context here, the deployment will be generated only in XMLtree
		
		// ====================================== load deployment file
		XMLTree XMLtree = XMLParser.validateParse(deployment.getSingleValue(CategoryName.SCHEMA.s()),
				deployment.getSingleValue(CategoryName.DEPLOYMENT_FILE.s()));
		if(loadedXML != null)
			loadedXML.set(XMLtree);
		if(XMLtree != null)
		{
			context = new LinkedList<>();
			readXML(XMLtree.getRoot(), deployment, context, this, log);
			log.lf("after XML tree parse:", this);
			log.lf(">>>>>>>>");
		}
		else
			log.le("Deployment file load failed.");
		
		// ====================================== parse CLI args
		Iterator<String> it = programArguments.iterator();
		if(scenarioFirst) // already processed
			it.next();
		readCLIArgs(it, new CtxtTriple(CategoryName.DEPLOYMENT.s(), deployment, deployment.getSingleTree(null, true)),
				this, log);
		log.lf("after CLI tree parse:", this);
		
		// ====================================== port portables
		
		MultiTreeMap allNodes = this.getSingleTree(CategoryName.NODE.s());
		for(String catName : this.getKeys())
		{
			if(CategoryName.byName(catName) == null || CategoryName.byName(catName).visibilityScope() == null
					|| !CategoryName.byName(catName).visibilityScope().getAncestorsList()
							.contains(CategoryName.NODE.s()))
				// cannot compute visibility or category not visible to a descendant of node
				continue;
			CategoryName cat = CategoryName.byName(catName);
			for(String name : allNodes.getHierarchicalNames())
				// for all nodes
				for(MultiTreeMap node : allNodes.getTrees(name))
					if(!node.getKeys().contains(catName) || !cat.isUnique())
					{ // if node has no entry for the category or if the category is not unique
						if(this.isSimple(catName))
							if(this.isSingleton(catName))
								node.addSingleValue(catName, this.getSingleValue(catName));
							else
								node.addAll(catName, this.getValues(catName));
						else if(this.isSingleton(catName))
							node.addSingleTree(catName, this.getSingleTree(catName));
						else
							node.addTrees(catName, this.getTrees(catName));
					}
		}
		log.lf("final config:", this);
		
		log.doExit();
		lock();
		return this;
	}
	
	/**
	 * Recursive method (recursing on XML nodes) which reads data from an XML (sub-)tree from the deployment file into
	 * the given configuration tree.
	 * <p>
	 * While processing the current XML node the corresponding entity node is created. The category node must be created
	 * while processing the parent, so as to add multiple entities to the same category. The node will add itself to its
	 * context category.
	 * 
	 * It also:
	 * <ul>
	 * <li>assigns names to entities, potentially auto-generated, based on the rules in {@link CategoryName};
	 * <li>assigns contexts in the tree structure based on the value of the {@value #CONTEXT_ELEMENT_NAME} attributes;
	 * <li>creates {@value #CONTEXT_ELEMENT_NAME} parameters based on tree structure;
	 * <li>adds entities to the entity list, using the actual subtrees in the configuration tree;
	 * </ul>
	 * 
	 * @param XMLnode
	 *            - the XML node to read.
	 * @param catTree
	 *            - the configuration tree corresponding to category containing this node.
	 * @param context
	 *            - the context of the current node, down to the parent entity of this node.
	 * @param rootTree
	 *            - the root deployment tree, where identifiable entities should be added.
	 * @param log
	 *            - the {@link Logger} to use.
	 */
	protected static void readXML(XMLNode XMLnode, MultiTreeMap catTree, Deque<CtxtTriple> context,
			MultiTreeMap rootTree, Logger log)
	{
		// String l = "Node " + node.getName() + " with attributes ";
		// for(XMLAttribute a : node.getAttributes())
		// l += a.getName() + ",";
		// l += " and children ";
		// for(XMLNode n : node.getNodes())
		// l += n.getName() + ",";
		// log.lf(l);
		
		// get information on the node's category
		String catName = getXMLNodeCategory(XMLnode);
		CategoryName category = CategoryName.byName(catName);
		
		// create node
		MultiTreeMap nodeTree = new MultiTreeMap();
		
		if(!context.isEmpty()) // not at root
			// read attributes, transform them to parameters
			for(XMLAttribute a : XMLnode.getAttributes())
				addParameter(nodeTree, a.getName(), a.getValue(), false, log);
			
		// add self to context
		context.push(new CtxtTriple(catName, catTree, nodeTree));
		
		// check subordinate XML nodes and integrate them.
		// two passes, because a name must be generated (for adding context to subordinate nodes) before checking
		// subordinate nodes.
		ArrayList<XMLNode> childEntities = new ArrayList<>();
		for(XMLNode child : XMLnode.getNodes())
		{
			if(child.getName().equals(PARAMETER_ELEMENT_NAME))
				// parameter nodes, add their values to the current tree
				addParameter(nodeTree, child.getAttributeValue(PARAMETER_NAME),
						child.getAttributeValue(PARAMETER_VALUE), false, log);
			else if(child.getNodes().isEmpty() && child.getAttributes().isEmpty()
					&& (CategoryName.byName(getXMLNodeCategory(child)) == null
							|| CategoryName.byName(getXMLNodeCategory(child)).isValue()))
				// text node, that will also be treated as parameter - value
				addParameter(nodeTree, child.getName(), (String) child.getValue(),
						(CategoryName.byName(getXMLNodeCategory(child)) != null
								&& CategoryName.byName(getXMLNodeCategory(child)).isUnique()),
						log);
			else
				childEntities.add(child);
		}
		
		// get the node's name or create it according to the child's category / entity; add to entity list
		// is here in order to be after checking subordinate parameter nodes (for name or name parts)
		integrateName(nodeTree, category, catTree, rootTree, log);
		
		// add context of the entity
		addContext(context, nodeTree, catName, log);
		
		for(XMLNode child : childEntities)
		{
			// node must be integrated as a different entity
			String childCatName = getXMLNodeCategory(child);
			// create implicit root entity, if necessary
			manageImplicitRoot(CategoryName.byName(childCatName), rootTree, context);
			MultiTreeMap childCatTree = integrateChildCat(context.getFirst().elemTree, context.getFirst().category,
					childCatName, log);
			if(childCatTree == null)
				continue;
			// process child
			readXML(child, childCatTree, new LinkedList<>(context), rootTree, log);
		}
	}
	
	/**
	 * Reads data from program arguments into the given configuration tree. The parser attempts to place the parameters
	 * in the correct categories / elements in the already existing tree (read from the XML) or introduce new elements
	 * at the correct places. The CLI arguments are parsed in order as follows:
	 * <ul>
	 * <li>if the argument is a category / entity ("-category"), its correct place in the tree is found, either by
	 * advancing in the tree or going upwards in the tree until in the correct context.
	 * <li>the category name must be immediately followed by the element name (may be new or existing).
	 * <li>what follows until the next category name are arguments of the form "parameter:value" or just "parameter".
	 * </ul>
	 * <p>
	 * For integrating entities that are not already in the XML, a stack is used to keep track of the current position
	 * in the tree. The "current position" in the tree is decided by the existing tree (for existing elements and
	 * entities), by the hierarchy described in {@link CategoryName}, for known entities, and otherwise each new entity
	 * is considered as subordinate to the previous entity and for known entities the stack is popped until getting to
	 * the level where the entity appeared previously.
	 * 
	 * @param args
	 *            - an {@link Iterator} through the arguments.
	 * @param baseContext
	 *            - the {@link CategoryName#DEPLOYMENT} context entry.
	 * @param rootTree
	 *            - the configuration tree. The given tree is expected to already contain the data from the XML
	 *            deployment file.
	 * @param log
	 *            - the {@link Logger} to use.
	 */
	protected static void readCLIArgs(Iterator<String> args, CtxtTriple baseContext, MultiTreeMap rootTree,
			UnitComponentExt log)
	{
		Deque<CtxtTriple> context = new LinkedList<>();
		context.push(baseContext);
		while(args.hasNext())
		{
			// log.lf(context.toString());
			String a = args.next();
			if(a.trim().length() == 0)
				continue;
			if(isCategory(a))
			{
				// get category
				String catName = getCategory(a);
				CategoryName category = CategoryName.byName(getCategory(a));
				if(!args.hasNext())
				{ // must check this before creating any trees
					log.lw("Empty unknown category [] in CLI arguments.", catName);
					return;
				}
				// get name
				String name = args.next();
				
				// create / find the context
				// search upwards in the current context
				// save the current context, in case no appropriate context found upwards
				Deque<CtxtTriple> savedContext = new LinkedList<>(context);
				while(!context.isEmpty())
				{
					if(context.peek().elemTree.isHierarchical(catName)
							|| (category != null && context.peek().category.equals(category.getParent()))) // TODO use
																											// ancestor
																											// list
					{ // found a level that contains the same category;
						// will insert new element in this context
						// childCatTree = context.peek().elemTree.getSingleTree(catName);
						break;
					}
					// no match yet
					context.pop();
				}
				if(context.isEmpty())
				{
					String msg = "Category [] has parent [] and no instance of parent could be found;";
					if(category == null)
					{ // category not known a-priori
						log.lw(msg + " adding in current context.", catName, "unknown");
						context = new LinkedList<>(savedContext);
					}
					else
					{
						log.lw(msg + " adding to top level.", catName, category.getParent());
						context = new LinkedList<>();
						context.add(baseContext);
					}
				}
				// create implicit root entity, if necessary
				manageImplicitRoot(category, rootTree, context);
				
				// integrate in current context.
				CtxtTriple cCtxt = context.peek();
				
				MultiTreeMap subCatTree = integrateChildCat(cCtxt.elemTree, cCtxt.category, catName, log);
				MultiTreeMap node;
				boolean newNode = false;
				if(subCatTree.isHierarchical(name))
					node = subCatTree.isSingleton(name) ? subCatTree.getSingleTree(name)
							: subCatTree.getFirstTree(name);
				else
				{
					node = new MultiTreeMap();
					node.addOneValue(NAME_ATTRIBUTE_NAME, name);
					integrateName(node, category, subCatTree, rootTree, log);
					newNode = true;
				}
				context.push(new CtxtTriple(catName, subCatTree, node));
				if(newNode)
					addContext(context, node, catName, log);
			}
			else
			{
				if(context.size() <= 1)
				{
					log.le("cannot add parameters to the root context (parameter was [])", a);
					continue;
				}
				if(context.peek().elemTree == null)
				{
					log.le("incorrect context for parameter []", a);
					continue;
				}
				String parameter, value = null;
				if(a.contains(NAME_SEPARATOR))
				{ // parameter name & value
					String[] es = a.split(NAME_SEPARATOR, 2);
					parameter = es[0];
					value = es[1];
				}
				else
					parameter = a;
				addParameter(context.peek().elemTree, parameter, value,
						(CategoryName.byName(parameter) != null && CategoryName.byName(parameter).isUnique()), log);
			}
		}
	}
	
	/**
	 * Checks if the given command line argument designates a category (begins with {@value #CLI_CATEGORY_PREFIX}).
	 * 
	 * @param arg
	 *            - the argument.
	 * @return <code>true</code> if it designates a category.
	 */
	protected static boolean isCategory(String arg)
	{
		return arg.startsWith(CLI_CATEGORY_PREFIX);
	}
	
	/**
	 * Returns the actual category name designated by a command line argument (removes preceding
	 * {@value #CLI_CATEGORY_PREFIX})
	 * 
	 * @param arg
	 *            - the argument.
	 * @return the category name.
	 */
	protected static String getCategory(String arg)
	{
		return isCategory(arg) ? arg.substring(1) : null;
	}
	
	/**
	 * Common XML/CLI functionality: add a parameter and its value to a tree; if already added as a single, overwrite.
	 * It is added as a multiple name by default. it..
	 * 
	 * @param asSingleton
	 *            - if the parameter is a singleton value.
	 */
	@SuppressWarnings("javadoc")
	protected static void addParameter(MultiTreeMap node, String par, String val, boolean asSingleton, Logger log)
	{
		if(node.containsHierarchicalName(par))
			log.le("Name [] already present as hierarchical name.", par);
		else if(asSingleton)
			node.setValue(par, val);
		else
			node.addOneValue(par, val);
	}
	
	/**
	 * Common XML/CLI functionality: retrieve or create a category node for a child entity inside the node of a parent
	 * entity.
	 * <p>
	 * Checks if it is correct to nest the child category inside the parent category.
	 * <p>
	 * If the child category is unique, checks if there is an existing entity in that category.
	 */
	@SuppressWarnings("javadoc")
	protected static MultiTreeMap integrateChildCat(MultiTreeMap parentNodeTree, String parentCat, String subCatName,
			Logger log)
	{
		CategoryName subCat = CategoryName.byName(subCatName);
		if(subCat != null && !subCat.isParentOptional() && !parentCat.equals(subCat.getParent())
				&& !(ROOT_CATEGORY.s().equals(subCat.getParent()) && subCat.visibilityScope() != null))
		{ // incorrect placement
			log.le("Incorrect placement for [] entity in context of [] entity.", subCatName, parentCat);
			return null;
		}
		if(parentNodeTree.containsKey(subCatName))
		{
			if(parentNodeTree.isHierarchical(subCatName))
			{
				if(subCat != null && subCat.isUnique()
						&& !parentNodeTree.getSingleTree(subCatName).getTreeKeys().isEmpty())
				// already other children present
				{
					log.le(null, "Cannot add additional children to unique category ", subCatName);
					return null;
				}
				return parentNodeTree.getSingleTree(subCatName);
			}
			log.le(null, "Child node category [] is already present as a simple key. Will not process this node.",
					subCat);
			return null;
		}
		// category not already present
		return parentNodeTree.addSingleTreeGet(subCatName, new MultiTreeMap());
	}
	
	/**
	 * Common XML/CLI functionality: When an entity in the given category is added at the root level, checks if it is
	 * necessary to add an implicit entity in {@link #ROOT_CATEGORY} and adds it to the parent node and to the current
	 * context.
	 * <p>
	 * It will not be added to the entity list as it does not have a name.
	 * <p>
	 * Conversely, if the entity should be added <i>outside</i> the root level (at deployment level), context is
	 * modified accordingly (the root entity is removed from the context).
	 * 
	 * TODO: is there really a need for the return value anymore?
	 * 
	 * @return if a {@link #ROOT_CATEGORY} entity has been added, a new context entry corresponding to that entity;
	 *         <code>null</code> otherwise.
	 */
	@SuppressWarnings("javadoc")
	protected static CtxtTriple manageImplicitRoot(CategoryName category, MultiTreeMap rootTree,
			Deque<CtxtTriple> context)
	{
		CtxtTriple ctxt = context.getFirst();
		if(ROOT_CATEGORY.s().equals(ctxt.category)
				&& ctxt.elemTree == (ROOT_CATEGORY.isUnique() ? ctxt.catTree.getSingleTree(null)
						: ctxt.catTree.getFirstTree(null))
				&& category != null
				&& (category == ROOT_CATEGORY || !category.getAncestorsList().contains(ROOT_CATEGORY.s())
						|| (ROOT_CATEGORY.s().equals(category.getParent()) && category.visibilityScope() != null)))
		{
			context.pop();
			return null;
			// FIXME: doesn't work
		}
		
		if(context.size() != 1)
			// not at deployment level
			return null;
			
		// if the category is not known
		// or the category is a descendant of the root category
		// but is not a direct child of the root category which has a visibility scope (in this case it can be ported to
		// its actual parent)
		// must create / get implicit node
		if(category != null && (category == ROOT_CATEGORY || !category.getAncestorsList().contains(ROOT_CATEGORY.s())
				|| (ROOT_CATEGORY.s().equals(category.getParent()) && category.visibilityScope() != null)))
			return null;
			
		// search root entity, or create it
		// FIXME: some code duplication with #integrateName
		MultiTreeMap parentElem = context.getFirst().elemTree;
		MultiTreeMap rootCat = parentElem.getSingleTree(ROOT_CATEGORY.s(), true);
		boolean existing = rootCat.containsHierarchicalName(null);
		MultiTreeMap rootEntity = ROOT_CATEGORY.isUnique() ? rootCat.getSingleTree(null, true)
				: rootCat.getFirstTree(null, true);
		
		if(!existing)
		{
			if(ROOT_CATEGORY.isUnique())
				rootTree.getSingleTree(ROOT_CATEGORY.s(), true).addSingleTree(null, rootEntity);
			else
				rootTree.getSingleTree(ROOT_CATEGORY.s(), true).addOneTree(null, rootEntity);
			
			String id = "#" + rootEntity.hashCode();
			rootTree.getSingleTree(NAME_LIST_ENTRY, true).addSingleTree(id, rootEntity);
			rootEntity.addSingleValue(NAME_LIST_ENTRY, id);
		}
		
		context.push(new CtxtTriple(ROOT_CATEGORY.s(), rootCat, rootEntity));
		return context.getFirst();
	}
	
	/**
	 * Common XML/CLI functionality: this method does the following:
	 * <ul>
	 * <li>if there is no existing name of the entity, attempts to generate a name based on other attributes of the
	 * entity (see name parts in CategoryName) and integrates the generated name in the entity's tree.
	 * <li>integrates the tree describing the entity into the tree of its category, under the given or generated name
	 * (or under the <code>null</code> name, if no name could be created. It is added as a singleton value or not
	 * depending on the value returned by {@link CategoryName#isUnique()}.
	 * <li>if the category is identifiable, adds the entity to the global <i>entity list</i>; if the entity has no name,
	 * a generated id is used.
	 * <li>the entity is added to the global <i>name list</i>; if the entity has no name, the generated id is used.\
	 * </ul>
	 * 
	 * @param node
	 *            - the tree describing the entity.
	 * @param category
	 *            - the category of the entity.
	 * @param catTree
	 *            - the tree describing the category of the entity.
	 * @param rootTree
	 *            - the tree describing the entire deployment.
	 * @param log
	 *            - the {@link Logger} to use.
	 * 
	 * @return the name of the entity that
	 */
	protected static String integrateName(MultiTreeMap node, CategoryName category, MultiTreeMap catTree,
			MultiTreeMap rootTree, Logger log)
	{
		String name = node.getFirstValue(NAME_ATTRIBUTE_NAME);
		boolean nameGenerated = name == null;
		if(name == null && category != null && category.hasNameWithParts())
		{ // node has a registered category and its elements have two-parts names
			String[] partNames = category.nameParts();
			String part1 = node.getFirstValue(partNames[0]);
			String part2 = node.getFirstValue(partNames[1]);
			if(part2 == null && !category.isNameSecondPartOptional())
				return (String) log.lr(null, "Node of [] entity does not contain necessary name part attribute [].",
						category.s(), partNames[0]);
			name = (part1 != null ? part1 : "") + (part2 != null ? NAME_SEPARATOR + part2 : "");
		}
		if(name != null && name.trim().length() == 0)
			name = null; // no 0-length names
		if(nameGenerated && name != null && !node.containsKey(NAME_ATTRIBUTE_NAME))
			// add name parameter containing the generated name
			node.addOneValue(NAME_ATTRIBUTE_NAME, name);
		
		// add to category containing this entity
		if(catTree != null)
		{
			if(category != null && category.isUnique())
				catTree.addSingleTree(name, node);
			else
				catTree.addOneTree(name, node);
		}
		
		// add to entity list if the entity is identifiable (even if no name)
		String id = "#" + node.hashCode();
		if(category != null && category.isIdentifiable())
		{
			if(category.isUnique())
				rootTree.getSingleTree(category.s(), true).addSingleTree(name, node);
			else
				rootTree.getSingleTree(category.s(), true).addOneTree(name, node);
		}
		
		// add to name list, with id as fallback for name
		if(category != null && name != null && category.isIdentifiable())
			rootTree.getSingleTree(NAME_LIST_ENTRY, true).addSingleTree(name, node);
		else if(category != CategoryName.DEPLOYMENT)
		{
			rootTree.getSingleTree(NAME_LIST_ENTRY, true).addSingleTree(id, node);
			node.addSingleValue(NAME_LIST_ENTRY, id);
		}
		
		return name;
	}
	
	/**
	 * Looks into the entire context of an entity and adds the identifiers of the visible context elements to the
	 * {@value #CONTEXT_ELEMENT_NAME} entry in the tree.
	 * 
	 * @param exteriorContext
	 *            - the context of the entity, with the deepest (closest) context first, starting with the entity
	 *            itself.
	 * @param nodeTree
	 *            - the tree describing the entity.
	 * @param categoryName
	 *            - the category of the entity.
	 * @param log
	 *            - the {@link Logger} to use.
	 */
	protected static void addContext(Deque<CtxtTriple> exteriorContext, MultiTreeMap nodeTree, String categoryName,
			Logger log)
	{
		if(exteriorContext.isEmpty())
			return;
		int idx = -1;
		List<String> contextSuffix = new ArrayList<>(); // deepest-first visited categories
		for(CtxtTriple cElement : exteriorContext)
		{
			idx++;
			switch(idx)
			{
			case 0:
				// first context element is the entity itself
				continue;
			case 1:
				// add the immediate context anyway
				addContext(cElement, nodeTree);
				break;
			default:
				CategoryName contextCat = CategoryName.byName(cElement.category);
				CategoryName category = CategoryName.byName(categoryName);
				boolean visible = (contextCat != null && contextCat.visibilityScope() == CategoryName.DEPLOYMENT)
						|| (category != null && cElement.category != null
								&& cElement.category.equals(category.getParent()))
						|| (contextCat != null && contextCat.visibilityScope() != null
								&& !contextSuffix.contains(contextCat.visibilityScope().s()));
				if(visible)
				{
					log.lf("Added context []:[] to entity []:[]", cElement.category,
							cElement.elemTree.getFirstValue(NAME_ATTRIBUTE_NAME), categoryName,
							nodeTree.getFirstValue(NAME_ATTRIBUTE_NAME));
					addContext(cElement, nodeTree);
				}
			}
			contextSuffix.add(cElement.category);
		}
		
	}
	
	/**
	 * Adds the context element as context to the node, in the {@value #CONTEXT_ELEMENT_NAME} tree entry.
	 * 
	 * @param contextElement
	 *            - the context.
	 * @param nodeTree
	 *            - the node.
	 */
	protected static void addContext(CtxtTriple contextElement, MultiTreeMap nodeTree)
	{
		if(CategoryName.DEPLOYMENT.s().equals(contextElement.category))
			return;
		CategoryName cat = CategoryName.byName(contextElement.category);
		String ref;
		if(cat != null && cat.isIdentifiable() && contextElement.elemTree.isSimple(NAME_ATTRIBUTE_NAME)
				&& !("" + null).equals(contextElement.elemTree.getFirstValue(NAME_ATTRIBUTE_NAME)))
			ref = contextElement.elemTree.getFirstValue(NAME_ATTRIBUTE_NAME);
		else
			ref = contextElement.elemTree.getSingleValue(NAME_LIST_ENTRY);
		nodeTree.addOneValue(CONTEXT_ELEMENT_NAME, ref);
	}
	
	@SuppressWarnings("javadoc")
	protected static String getXMLNodeCategory(XMLNode XMLnode)
	{
		String catName = XMLnode.getName();
		if(catName.equals(GENERAL_ENTITY_NAME))
			catName = XMLnode.getAttributeValue(GENERAL_ENTITY_TYPE_ATTRIBUTE);
		return catName;
	}
	
	/**
	 * Method to simplify the access to a parameter/attribute of an parametric XML node.
	 * <p>
	 * Having the {@link XMLNode} instance associated with the parametric node, the method retrieves the first value
	 * found among the following:
	 * <ul>
	 * <li>the value of the attribute with the searched name, if any, or otherwise
	 * <li>the value associated with the first occurrence of the desired parameter name, if any, or otherwise
	 * <li>the value in the first node with the searched name, if any.
	 * </ul>
	 * 
	 * @param node
	 *            - the node containing the configuration information for the agent.
	 * @param searchName
	 *            - the name of the searched attribute / parameter / node.
	 * @return the value associated with the searched name, or <code>null</code> if nothing found.
	 */
	public static String getXMLValue(XMLNode node, String searchName)
	{
		if(node.getAttributeValue(searchName) != null)
			// from an attribute
			return node.getAttributeValue(searchName);
		if(node.getAttributeOfFirstNodeWithValue(PARAMETER_ELEMENT_NAME, PARAMETER_NAME, searchName,
				PARAMETER_VALUE) != null)
			// from a parameter (e.g. <parameter name="search" value="the name">)
			return node.getAttributeOfFirstNodeWithValue(PARAMETER_ELEMENT_NAME, PARAMETER_NAME, searchName,
					PARAMETER_VALUE);
		if(node.getNode(searchName, 0) != null)
			return node.getNode(searchName, 0).getValue().toString();
		return null;
	}
	
}
