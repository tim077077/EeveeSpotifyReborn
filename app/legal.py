"""Privacy policy and terms pages. TikTok's app review requires public URLs for both.

They describe what this software actually does with TikTok data. They are a starting point,
not legal advice - review them for your jurisdiction before submitting.
"""

from __future__ import annotations

from datetime import date
from html import escape

from .config import settings

_STYLE = """<style>
body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Inter,sans-serif;max-width:760px;margin:40px auto;padding:0 16px;
color:#1d1d1f;background:#fff}h1{font-size:28px}h2{font-size:19px;margin-top:28px}
@media(prefers-color-scheme:dark){body{color:#ecedf0;background:#0e0f12}a{color:#7fb3ff}}</style>"""


def _page(title: str, body: str) -> str:
    app = escape(settings.app_name)
    return (
        f"<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
        f"<title>{escape(title)} — {app}</title>{_STYLE}</head><body><h1>{escape(title)}</h1>"
        f"<p><i>Last updated {date.today():%B %d, %Y}</i></p>{body}</body></html>"
    )


def _contact() -> str:
    email = settings.contact_email
    return f'<a href="mailto:{escape(email)}">{escape(email)}</a>' if email else "the operator of this website"


def privacy_html() -> str:
    app = escape(settings.app_name)
    return _page("Privacy Policy", f"""
<p>{app} is a tool that helps its users create image slideshows and publish them to their own TikTok accounts.
This policy explains what data {app} handles and why.</p>
<h2>Data we receive from TikTok</h2>
<p>When you connect a TikTok account, you sign in on TikTok and choose to grant {app} access. We then receive:</p>
<ul>
<li><b>Basic profile information</b> (display name, username, avatar, an account identifier) - to show which account content is posted to.</li>
<li><b>Access tokens</b> - to publish the content you create or schedule, on your behalf.</li>
<li><b>Statistics for videos posted through {app}</b> (views, likes, comments, shares) - to show you how your posts perform.</li>
</ul>
<p>We only publish content you created or scheduled in {app}, with the settings you chose. We never post anything else,
send messages, or change your profile.</p>
<h2>How data is stored</h2>
<p>Data is stored on the server that runs {app}. Access tokens and API keys are encrypted at rest. We do not sell data,
share it with advertisers, or use it for any purpose other than operating the service for you.</p>
<h2>Service providers</h2>
<p>To generate slideshow text and images, the text of your prompts may be sent to the AI and image providers configured
by the operator. Your TikTok tokens and profile data are never sent to them.</p>
<h2>Your choices and deletion</h2>
<p>You can disconnect a TikTok account at any time in {app} (TikTok accounts page), which deletes its tokens, and you can
revoke access in TikTok's settings under Security &gt; Manage app permissions. To have all of your data deleted, contact
{_contact()}; we will delete it within 30 days.</p>
<h2>Children</h2><p>{app} is not intended for anyone under 18.</p>
<h2>Contact</h2><p>Questions about this policy: {_contact()}.</p>
""")


def terms_html() -> str:
    app = escape(settings.app_name)
    return _page("Terms of Service", f"""
<p>By using {app} you agree to these terms.</p>
<h2>The service</h2>
<p>{app} helps you create image slideshows and publish them to TikTok accounts you own or are authorized to manage.</p>
<h2>Your responsibilities</h2>
<ul>
<li>You must own, or have permission to use, every account you connect and all content you publish.</li>
<li>You are responsible for the content you publish, and it must follow
<a href="https://www.tiktok.com/community-guidelines">TikTok's Community Guidelines</a> and
<a href="https://www.tiktok.com/legal/terms-of-service">Terms of Service</a>.</li>
<li>Commercial content must be disclosed using the disclosure settings provided before posting.</li>
<li>Content generated with AI is labeled as AI-generated when published.</li>
</ul>
<h2>Availability</h2>
<p>The service is provided "as is", without warranties. We may change or discontinue it at any time. TikTok may limit
how many posts can be published per day; posts beyond that limit are saved as drafts instead.</p>
<h2>Liability</h2>
<p>To the extent permitted by law, we are not liable for indirect or consequential damages, including lost reach,
followers or revenue.</p>
<h2>Contact</h2><p>{_contact()}</p>
""")


def about_html() -> str:
    """Public landing page: the 'website URL' for TikTok's app review."""
    app = escape(settings.app_name)
    return _page(app, f"""
<p>{app} helps creators make image slideshows (photo carousels) and publish them to their own TikTok accounts.</p>
<h2>How it works</h2>
<ol>
<li><b>Connect your TikTok account</b> with Login Kit. You choose which permissions to grant on TikTok.</li>
<li><b>Create a slideshow</b>: describe a topic and {app} drafts the slide text and backgrounds. You can edit every slide.</li>
<li><b>Publish or schedule</b>: send it to your TikTok drafts, or post it. Before posting you see which account it goes to,
choose who can see it, set comments, disclose commercial content, and agree to TikTok's Music Usage Confirmation.</li>
<li><b>Track results</b>: see views, likes, comments and shares for posts made through {app}.</li>
</ol>
<h2>Permissions used</h2>
<ul>
<li><code>user.info.basic</code> - show the connected account's name and avatar.</li>
<li><code>video.upload</code> - send slideshows to your TikTok drafts.</li>
<li><code>video.publish</code> - post slideshows you approved.</li>
<li><code>video.list</code> - show statistics for posts made through {app}.</li>
</ul>
<p><a href="/privacy">Privacy Policy</a> · <a href="/terms">Terms of Service</a> · <a href="/">Sign in</a></p>
""")
