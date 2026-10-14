// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;
import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.*;
import com.intellij.psi.*;
import org.vernac.language.VernacNames;
public final class VernacNewApplicationAction extends AnAction implements DumbAware {
    private static final String FILE="vernac-application.vapplication";
    public VernacNewApplicationAction() {super("Vernac Application Definition","Create the Maven project's Vernac activation contract",VernacFileType.INSTANCE.getIcon());}
    @Override public ActionUpdateThread getActionUpdateThread() {return ActionUpdateThread.BGT;}
    private static PsiDirectory root(PsiDirectory directory) {
        while(directory!=null) {if(directory.findFile("pom.xml")!=null)return directory;directory=directory.getParentDirectory();}return null;
    }
    @Override public void update(AnActionEvent event) {
        var view=event.getData(LangDataKeys.IDE_VIEW);
        event.getPresentation().setEnabledAndVisible(event.getProject()!=null&&view!=null&&java.util.Arrays.stream(view.getDirectories()).anyMatch(d->{var r=root(d);return r!=null&&r.findFile(FILE)==null;}));
    }
    @Override public void actionPerformed(AnActionEvent event) {
        var project=event.getProject();var view=event.getData(LangDataKeys.IDE_VIEW);if(project==null||view==null)return;
        var directory=root(view.getOrChooseDirectory());if(directory==null)return;
        if(directory.findFile(FILE)!=null) {Messages.showErrorDialog(project,"This Maven project already has a application definition.","New Vernac Application Definition");return;}
        String name=Messages.showInputDialog(project,"Fully qualified application identifier:","New Vernac Application Definition",Messages.getQuestionIcon(),"example.application",new InputValidator(){
            @Override public boolean checkInput(String input) {return VernacNames.isNamespace(input)&&input.contains(".");}
            @Override public boolean canClose(String input) {return checkInput(input);}
        });
        if(name==null)return;
        WriteCommandAction.runWriteCommandAction(project,()->{
            var file=PsiFileFactory.getInstance(project).createFileFromText(FILE,VernacApplicationFileType.INSTANCE,"application "+name+" {\n    // Module activation and bindings will be configured here.\n}\n");
            view.selectElement(directory.add(file));
        });
    }
}
