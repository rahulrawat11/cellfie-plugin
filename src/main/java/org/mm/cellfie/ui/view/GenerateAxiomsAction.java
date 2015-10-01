package org.mm.cellfie.ui.view;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.mm.cellfie.ui.exception.CellfieException;
import org.mm.cellfie.ui.view.ApplicationView.RenderLogging;
import org.mm.core.TransformationRule;
import org.mm.parser.ParseException;
import org.mm.renderer.RendererException;
import org.mm.rendering.Rendering;
import org.mm.rendering.owlapi.OWLAPIRendering;
import org.mm.ss.SpreadSheetDataSource;
import org.mm.ss.SpreadSheetUtil;
import org.mm.ss.SpreadsheetLocation;
import org.mm.ui.DialogManager;
import org.protege.editor.core.ui.util.JOptionPaneEx;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.ui.ontology.OntologyPreferences;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;

public class GenerateAxiomsAction implements ActionListener
{
   private ApplicationView container;

   private OWLEditorKit editorKit;
   private OWLModelManager modelManager;

   private static final int CANCEL_IMPORT = 0;
   private static final int ADD_TO_NEW_ONTOLOGY = 1;
   private static final int ADD_TO_CURRENT_ONTOLOGY = 2;

   public GenerateAxiomsAction(ApplicationView container)
   {
      this.container = container;
      editorKit = container.getEditorKit();
      modelManager = container.getEditorKit().getModelManager();
   }

   @Override
   public void actionPerformed(ActionEvent e)
   {
      try {
         SpreadSheetDataSource dataSource = container.getActiveWorkbook();
         Workbook workbook = dataSource.getWorkbook();

         List<TransformationRule> rules = getUserRules();
         if (rules.isEmpty()) {
            throw new CellfieException("No transformation rules created");
         }

         // TODO: Move this business logic inside the renderer
         Set<Rendering> results = new HashSet<Rendering>();
         for (TransformationRule rule : rules) {
            if (rule.isActive()) {
               String sheetName = rule.getSheetName();
               Sheet sheet = workbook.getSheet(sheetName);
               int startColumn = SpreadSheetUtil.columnName2Number(rule.getStartColumn());
               int startRow = SpreadSheetUtil.row2Number(rule.getStartRow());
               int endColumn = rule.hasEndColumnWildcard() ? sheet.getRow(startRow).getLastCellNum()
                     : SpreadSheetUtil.columnName2Number(rule.getEndColumn());
               int endRow = rule.hasEndRowWildcard() ? sheet.getLastRowNum()
                     : SpreadSheetUtil.row2Number(rule.getEndRow());

               if (startColumn > endColumn) {
                  throw new CellfieException("Start column after finish column in rule " + rule);
               }
               if (startRow > endRow) {
                  throw new CellfieException("Start row after finish row in rule " + rule);
               }

               SpreadsheetLocation endLocation = new SpreadsheetLocation(sheetName, endColumn, endRow);
               SpreadsheetLocation startLocation = new SpreadsheetLocation(sheetName, startColumn, startRow);
               SpreadsheetLocation currentLocation = new SpreadsheetLocation(sheetName, startColumn, startRow);

               dataSource.setCurrentLocation(currentLocation);
               do {
                  evaluate(rule, results, container.getRenderLogging());
                  if (currentLocation.equals(endLocation)) {
                     break;
                  }
                  currentLocation = incrementLocation(currentLocation, startLocation, endLocation);
                  dataSource.setCurrentLocation(currentLocation);
               } while (true);
            }
         }
         showAxiomPreviewDialog(toAxioms(results));
         saveLogging();
      } catch (Exception ex) {
         getApplicationDialogManager().showErrorMessageDialog(container, ex.getMessage());
      }
   }

   private void saveLogging() throws FileNotFoundException
   {
      container.getRenderLogging().save();
   }

   private Set<OWLAxiom> toAxioms(Set<Rendering> results)
   {
      Set<OWLAxiom> axiomSet = new HashSet<OWLAxiom>();
      for (Rendering rendering : results) {
         if (rendering instanceof OWLAPIRendering) {
            axiomSet.addAll(((OWLAPIRendering) rendering).getOWLAxioms());
         }
      }
      return axiomSet;
   }

   private void showAxiomPreviewDialog(Set<OWLAxiom> axioms) throws CellfieException
   {
      final ImportOption[] options = { new ImportOption(CANCEL_IMPORT, "Cancel"),
            new ImportOption(ADD_TO_NEW_ONTOLOGY, "Add to a new ontology"),
            new ImportOption(ADD_TO_CURRENT_ONTOLOGY, "Add to current ontology") };
      try {
         OWLOntology currentOntology = container.getActiveOntology();
         int answer = JOptionPaneEx.showConfirmDialog(container, "Generated Axioms", createPreviewAxiomsPanel(axioms),
               JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options[1]);
         switch (answer) {
            case ADD_TO_CURRENT_ONTOLOGY :
               modelManager.applyChanges(addAxioms(currentOntology, axioms));
               break;
            case ADD_TO_NEW_ONTOLOGY :
               OWLOntologyID id = createOntologyID();
               OWLOntology newOntology = modelManager.createNewOntology(id, id.getDefaultDocumentIRI().toURI());
               modelManager.applyChanges(addImport(newOntology, currentOntology));
               modelManager.applyChanges(addAxioms(newOntology, axioms));
               break;
         }
      } catch (ClassCastException e) {
         // NO-OP: Fix should be to Protege API
      } catch (OWLOntologyCreationException e) {
         throw new CellfieException("Error while creating a new ontology: " + e.getMessage());
      }
   }

   private List<? extends OWLOntologyChange> addImport(OWLOntology newOntology, OWLOntology currentOntology)
   {
      List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
      IRI ontologyIri = currentOntology.getOntologyID().getOntologyIRI();
      OWLImportsDeclaration importDeclaration = modelManager.getOWLDataFactory().getOWLImportsDeclaration(ontologyIri);
      changes.add(new AddImport(newOntology, importDeclaration));
      return changes;
   }

   private OWLOntologyID createOntologyID()
   {
      OntologyPreferences ontologyPreferences = OntologyPreferences.getInstance();
      IRI freshIRI = IRI.create(ontologyPreferences.generateNextURI());
      return new OWLOntologyID(freshIRI);
   }

   private List<OWLOntologyChange> addAxioms(OWLOntology ontology, Set<OWLAxiom> axioms)
   {
      List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
      for (OWLAxiom ax : axioms) {
         changes.add(new AddAxiom(ontology, ax));
      }
      return changes;
   }

   private JPanel createPreviewAxiomsPanel(Set<OWLAxiom> axioms)
   {
      return new PreviewAxiomsPanel(container, editorKit, axioms);
   }

   private void evaluate(TransformationRule rule, Set<Rendering> results, RenderLogging logging) throws ParseException
   {
      container.evaluate(rule, container.getDefaultRenderer(), results);
      container.log(rule, container.getLogRenderer(), logging);
   }

   private SpreadsheetLocation incrementLocation(SpreadsheetLocation current, SpreadsheetLocation start,
         SpreadsheetLocation end) throws RendererException
   {
      if (current.getPhysicalRowNumber() < end.getPhysicalRowNumber()) {
         return new SpreadsheetLocation(current.getSheetName(), current.getPhysicalColumnNumber(), current.getPhysicalRowNumber() + 1);
      }
      if (current.getPhysicalRowNumber() == end.getPhysicalRowNumber()) {
         if (current.getPhysicalColumnNumber() < end.getPhysicalColumnNumber()) {
            return new SpreadsheetLocation(current.getSheetName(), current.getPhysicalColumnNumber() + 1, start.getPhysicalRowNumber());
         }
      }
      throw new RendererException("incrementLocation called redundantly");
   }

   private List<TransformationRule> getUserRules()
   {
      return container.getMappingBrowserView().getTransformationRules();
   }

   private DialogManager getApplicationDialogManager()
   {
      return container.getApplicationDialogManager();
   }

   /**
    * A helper class for creating import axioms command buttons.
    */
   class ImportOption implements Comparable<ImportOption>
   {
      private int option;
      private String title;

      public ImportOption(int option, String title)
      {
         this.option = option;
         this.title = title;
      }

      public int get()
      {
         return option;
      }

      @Override
      public String toString()
      {
         return title;
      }

      @Override
      public int compareTo(ImportOption o)
      {
         return option - o.option;
      }
   }
}
