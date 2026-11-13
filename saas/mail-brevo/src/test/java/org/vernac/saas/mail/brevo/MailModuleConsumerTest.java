// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail.brevo;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.vernac.compiler.application.*;
import org.vernac.compiler.modules.*;
import org.vernac.compiler.persistence.SchemaModel;
import org.vernac.compiler.pipeline.VernacCompiler;
import org.vernac.saas.mail.domain.*;
import org.vernac.saas.mail.local.*;
class MailModuleConsumerTest {
 @TempDir Path root;
 @Test void compiledModulesDriveTheOrdinaryConsumerBindingWithoutRegeneratingProviders() throws Exception {
  var modules=ModuleArtifacts.read(List.of(Path.of("../mail/target/classes"),Path.of("../mail-local/target/classes"),Path.of("target/classes")));
  ModuleContracts.checkDependencies(modules);
  assertEquals(Set.of("vernac.saas.mail","vernac.saas.mail.local","vernac.saas.mail.brevo"),
   modules.stream().map(ModuleDescriptor::module).collect(java.util.stream.Collectors.toSet()));
  Path sources=root.resolve("model"),file=sources.resolve("consumer/mail/model.vernac");
  Files.createDirectories(file.getParent());
  Files.writeString(file,"namespace consumer.mail; import org.vernac.saas.mail.MailAddress; value Recipient(MailAddress);");
  var compiler=new VernacCompiler();var resolved=compiler.analyzeProject(sources,modules);
  var generated=compiler.generate(resolved,SchemaModel.empty(),Map.of());
  assertTrue(generated.generatedFiles().stream().noneMatch(f->f.packageName().startsWith("org.vernac.saas.mail")));
  var app=ApplicationDefinition.parse(root.resolve("vernac-application.vapplication"),
   "application consumer.mail { bind org.vernac.saas.mail.MailProvider from configuration \"vernac.mail.provider\"; }");
  var application=ApplicationBindings.generate(app,resolved,modules,generated.generatedFiles(),false);
  Path java=root.resolve("java"),classes=Files.createDirectories(root.resolve("classes"));
  generated.writeTo(java);for(var source:application.files())source.writeTo(java);
  var arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
  try(var files=Files.walk(java)){files.filter(p->p.toString().endsWith(".java")).map(Path::toString).forEach(arguments::add);}
  assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
  try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
   new ApplicationContextRunner().withClassLoader(loader)
    .withConfiguration(AutoConfigurations.of(LocalMailAutoConfiguration.class,BrevoMailAutoConfiguration.class))
    .withUserConfiguration(loader.loadClass("consumer.mail.configuration.__VernacApplication"))
    .withPropertyValues("vernac.mail.provider=org.vernac.saas.mail.local.NoopMailProvider").run(c->{
     assertNull(c.getStartupFailure());
     assertEquals(MailProviderMode.SIMULATED,c.getBean(MailProvider.class).mode());
     assertEquals(MailAcceptance.ACCEPTED,c.getBean(MailProvider.class).submit(BrevoMailTransportTest.message()).acceptance());
     assertEquals(1,c.getBean(LocalMailInbox.class).previews().size());
    });
  }
 }
}
