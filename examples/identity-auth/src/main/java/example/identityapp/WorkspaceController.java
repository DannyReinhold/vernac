// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.identityapp;
import example.identityapp.usecase.ViewWorkspace;
import org.springframework.web.bind.annotation.*;
@RestController
class WorkspaceController {
 private final ViewWorkspace view;
 WorkspaceController(ViewWorkspace view){this.view=view;}
 @GetMapping(value="/workspace",produces="text/html;charset=UTF-8") String workspace(){
  view.execute(); // actual generated REQUIRED usecase and permission guard
  return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Workspace</title><h1>Your workspace</h1><p>Your verified workspace is open.</p><p><a href=\"/auth/accounts\">Your workspaces</a></p></html>";
 }
}
