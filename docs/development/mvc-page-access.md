# MVC page access in Vernac 0.6.0

Declare one access mode for the entire inbound MVC adapter. Group pages by who can visit them. In the SaaS example, edit `web/public-pages.vernac` for the visitor journey and `web/pages.vernac` for the signed-in workspace.

```vernac
namespace example.documentworkspace.web;

adapter PublicPages inbound mvc {
    access public;

    home {
        GET "/";
        render "home";
    }
}
```

The base defaults to `/`. A plain GET can render a view without a use case, input bindings or a result model. The logical name `home` selects `src/main/resources/templates/home.html` in the same project. Use Go to Definition on the name to open the template. HTML owns layout, text and links.

A public declaration opens only the adapter's declared method/path combinations. The generated public security configuration requires Spring Security. An authenticated adapter requires a signed-in request. The application supplies login and session handling. Use case guards separately enforce permissions and tenant ownership for every caller. Existing adapters without an access declaration continue to use the application's existing security policy.

Verify changes in a newly generated SaaS project. Visit the root anonymously, follow the imprint and login links, and register through the home page. A direct anonymous request to the workspace must be rejected or directed to login. After verification, login and account selection, the workspace must allow the user's permitted document operations.
