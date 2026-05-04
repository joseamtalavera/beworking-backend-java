package com.beworking.leads;

public class LeadEmailService {

  // --- Contact-form templates -------------------------------------------------
  // Used when a lead arrives from the generic /contact form. The user reply is
  // generic with a subject-specific opening paragraph. The admin email surfaces
  // the subject + message verbatim so the team can triage.

  public static String getContactFormUserHtml(String name, String subject) {
    String intro = contactSubjectIntro(subject);
    String safeName = name == null ? "" : name;
    return String.format("""
    <!doctype html>
    <html lang=\"es\">
    <head>
      <meta charset=\"utf-8\">
      <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
      <title>BeWorking</title>
    </head>
    <body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">
      <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">
        <tr>
          <td align=\"center\" style=\"padding:0;margin:0;\">
            <table role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:24px auto;\">
              <tr>
                <td style=\"background:#0e0e0c;padding:28px 28px 20px 28px;color:#fff;border-radius:14px 14px 0 0;\">
                  <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:13px;letter-spacing:.4px;text-transform:uppercase;opacity:.75;\">BeWorking</div>
                  <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:26px;font-weight:700;line-height:1.2;margin-top:6px;\">
                    Hemos recibido tu mensaje
                  </div>
                </td>
              </tr>
              <tr>
                <td style=\"background:#ffffff;padding:24px 28px 28px 28px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:15px;line-height:1.6;color:#1d1d1f;\">
                  <p style=\"margin:0 0 14px;\">Hola <strong>%s</strong>,</p>
                  <p style=\"margin:0 0 14px;\">%s</p>
                  <p style=\"margin:0 0 14px;\">Te respondemos en menos de un día hábil. Si necesitas hablar con alguien antes, llámanos al
                    <a href=\"tel:+34951905967\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 951 905 967</a>
                    o escríbenos por WhatsApp:
                    <a href=\"https://wa.me/34640369759\" style=\"color:#009624;text-decoration:none;font-weight:600;\">+34 640 369 759</a>.
                  </p>
                  <p style=\"margin:18px 0 0;color:#6b7280;font-size:13px;\">— Equipo BeWorking</p>
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </table>
    </body>
    </html>
    """, safeName, intro);
  }

  // Intro paragraph chosen by subject. Values match the keys in
  // common.json -> contact.form.subjects (booking app i18n).
  private static String contactSubjectIntro(String subject) {
    if (subject == null) {
      return "Gracias por escribirnos. Hemos recibido tu mensaje y un miembro del equipo te responderá en breve.";
    }
    switch (subject.trim()) {
      case "Visita":
      case "Tour":
        return "Gracias por solicitar una visita. Te contactaremos en breve para confirmar día y hora, y te enseñaremos el espacio en persona.";
      case "Oficina Digital":
        return "Gracias por tu interés en Oficina Digital. Un asesor revisará tu mensaje y te contactará para guiarte en el siguiente paso.";
      case "Espacios y reservas":
      case "Spaces & booking":
        return "Gracias por tu interés en nuestros espacios. Te contactaremos para coordinar una visita o resolver tus dudas sobre disponibilidad.";
      case "Plataforma y cuenta":
      case "Platform & account":
        return "Hemos recibido tu consulta sobre la plataforma. Nuestro equipo de soporte te responderá lo antes posible.";
      case "Facturación":
      case "Billing":
        return "Hemos recibido tu consulta de facturación. El equipo de administración te responderá en horas laborables.";
      case "Consulta general":
      case "General enquiry":
      case "Otro":
      case "Other":
      default:
        return "Hemos recibido tu mensaje y un miembro del equipo te responderá en breve.";
    }
  }

  public static String getContactFormAdminHtml(
      String name, String email, String phone, String subject, String message, String source,
      String gmailThreadLink, String mailtoLink, String waLink, String waWebLink) {
    String safePhone = (phone == null || phone.isBlank()) ? "—" : phone;
    String safeSubject = (subject == null || subject.isBlank()) ? "—" : subject;
    String safeMessage = (message == null || message.isBlank())
        ? "(sin mensaje)"
        : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    String safeSource = (source == null || source.isBlank()) ? "—" : source;
    return String.format("""
    <!doctype html>
    <html lang=\"es\">
    <head>
      <meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
      <title>Nuevo contacto</title>
    </head>
    <body style=\"margin:0;padding:0;background:#f7f7f8;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;\">
      <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">
        <tr>
          <td align=\"center\">
            <table role=\"presentation\" width=\"620\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:620px;max-width:620px;margin:24px auto;\">
              <tr>
                <td style=\"background:#0e0e0c;padding:20px 24px;color:#fff;border-radius:14px 14px 0 0;\">
                  <div style=\"font-size:12px;letter-spacing:.3px;text-transform:uppercase;opacity:.7;\">BeWorking · contact-form</div>
                  <div style=\"font-size:20px;font-weight:700;margin-top:6px;\">Nuevo mensaje recibido</div>
                  <div style=\"opacity:.75;font-size:13px;margin-top:2px;\">Asunto: %s · Origen: %s</div>
                </td>
              </tr>
              <tr>
                <td style=\"background:#fff;padding:18px 24px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">
                  <div style=\"padding:8px 0;border-bottom:1px dashed #eee;\">
                    <div style=\"color:#667085;font-size:11px;letter-spacing:.3px;text-transform:uppercase;\">Nombre</div>
                    <div style=\"font-size:15px;font-weight:600;color:#111;\">%s</div>
                  </div>
                  <div style=\"padding:8px 0;border-bottom:1px dashed #eee;\">
                    <div style=\"color:#667085;font-size:11px;letter-spacing:.3px;text-transform:uppercase;\">Email</div>
                    <div style=\"font-size:15px;font-weight:600;color:#111;\"><a href=\"mailto:%s\" style=\"color:#111;text-decoration:none;\">%s</a></div>
                  </div>
                  <div style=\"padding:8px 0;border-bottom:1px dashed #eee;\">
                    <div style=\"color:#667085;font-size:11px;letter-spacing:.3px;text-transform:uppercase;\">Teléfono</div>
                    <div style=\"font-size:15px;font-weight:600;color:#111;\">%s</div>
                  </div>
                  <div style=\"padding:12px 0 8px;\">
                    <div style=\"color:#667085;font-size:11px;letter-spacing:.3px;text-transform:uppercase;\">Mensaje</div>
                    <div style=\"font-size:14px;color:#1d1d1f;line-height:1.6;margin-top:4px;white-space:pre-wrap;\">%s</div>
                  </div>
                  <div style=\"padding:14px 0 4px;text-align:center;\">
                    <a href=\"%s\" style=\"background:#009624;color:#fff;text-decoration:none;padding:10px 14px;border-radius:10px;display:inline-block;font-weight:700;margin:0 4px;\">Responder en Gmail</a>
                    <a href=\"%s\" style=\"background:#1d1d1f;color:#fff;text-decoration:none;padding:10px 14px;border-radius:10px;display:inline-block;font-weight:700;margin:0 4px;\">Responder por email</a>
                  </div>
                  %s
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </table>
    </body>
    </html>
    """,
    safeSubject, safeSource,
    name == null ? "—" : name,
    email == null ? "" : email, email == null ? "—" : email,
    safePhone,
    safeMessage,
    gmailThreadLink == null ? "#" : gmailThreadLink,
    mailtoLink == null ? "#" : mailtoLink,
    buildPhoneAndWhatsappBlock(safePhone, waLink, waWebLink));
  }

  private static String buildPhoneAndWhatsappBlock(String phone, String waLink, String waWebLink) {
    if (phone == null || "—".equals(phone)) return "";
    return String.format(
      "<div style=\"padding:6px 0;text-align:center;font-size:13px;color:#555;\">" +
      "<a href=\"tel:%s\" style=\"color:#009624;text-decoration:none;font-weight:600;margin:0 8px;\">Llamar</a>" +
      "<a href=\"%s\" style=\"color:#009624;text-decoration:none;font-weight:600;margin:0 8px;\">WhatsApp</a>" +
      "<a href=\"%s\" style=\"color:#009624;text-decoration:none;font-weight:600;margin:0 8px;\">WhatsApp Web</a>" +
      "</div>",
      phone, waLink == null ? "#" : waLink, waWebLink == null ? "#" : waWebLink);
  }

  // --- Legacy OV-interest template (kept for backwards compatibility) -------
  public static String getUserHtml(String name) {
        return String.format("""
        <!doctype html>
        <html lang=\"es\">
        <head>
          <meta charset=\"utf-8\">
          <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
          <title>BeWorking</title>
          <style>
            @media (max-width:600px){
              .container{width:100%%!important}
              .h1{font-size:28px!important;line-height:1.2!important}
              .h2{font-size:20px!important}
              .btn-table{width:100%%!important}
              .btn-td{display:block!important;width:100%%!important;padding:0 0 12px!important}
              .btn-td .btn{display:inline-block!important;margin:0 auto!important}
            }
            .btn-table{margin:0 auto;text-align:center;border-collapse:separate;border-spacing:0}
            .btn-td{padding:0;text-align:center}
            .btn{background:#009e5c;color:#fff !important;text-decoration:none;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-weight:700;padding:14px 22px;border-radius:12px;display:inline-block;}
          </style>
        </head>
        <body style=\"margin:0;padding:0;background:#f7f7f8;-webkit-font-smoothing:antialiased;\">
          <span style=\"display:none!important;visibility:hidden;opacity:0;color:transparent;height:0;width:0;\">
            Activación rápida, precio fijo 15 €/mes, sin permanencia. — BeWorking
          </span>
          <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"background:#f7f7f8;\">
            <tr>
              <td align=\"center\" style=\"padding:0;margin:0;\">
                <table class=\"container\" role=\"presentation\" width=\"600\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:600px;max-width:600px;margin:0 auto;\">
                  <!-- Hero -->
                  <tr>
                    <td style=\"background:linear-gradient(90deg,#ff9800 0%%,#ffb74d 100%%);padding:32px 28px 24px 28px;color:#fff;border-radius:14px 14px 0 0;\">
                      <div style=\"font-size:14px;letter-spacing:.4px;text-transform:uppercase;opacity:.9;\">BeWorking</div>
                      <div class=\"h1\" style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:34px;font-weight:800;line-height:1.1;margin-top:8px;\">
                        Tu Oficina Virtual
                      </div>
                      <div class=\"h2\" style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:22px;font-weight:700;margin-top:6px;\">
                        por <span style=\"font-size:32px;\">15 €</span>/mes
                      </div>
                      <p style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:16px;line-height:1.6;margin:12px 0 0;\">
                        <strong>Hola %s</strong>, gracias por tu interés. Ya hemos recibido tus datos
                        y te contactaremos muy pronto. Mientras, puedes dar el siguiente paso:
                      </p>
                      <div style=\"height:16px;\"></div>
                      <table role=\"presentation\" class=\"btn-table\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">
                        <tr>
                          <td class=\"btn-td\">
                            <a href=\"https://be-working.com/contact\" class=\"btn\">
                              Mas acerca de BeWorking
                            </a>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                  <!-- Features -->
                  <tr>
                    <td style=\"background:#ffffff;padding:24px 28px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">
                      <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">
                        <tr>
                          <td width=\"50%%\" style=\"vertical-align:top;padding:6px 8px;\">
                            <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:15px;line-height:1.5;\">
                              ✨ <strong>Alta en minutos</strong><br>
                              Sin papeleo complicado.
                            </div>
                          </td>
                          <td width=\"50%%\" style=\"vertical-align:top;padding:6px 8px;\">
                            <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:15px;line-height:1.5;\">
                              🧾 <strong>Precio fijo</strong><br>
                              15 €/mes. Sin depósito ni permanencia.
                            </div>
                          </td>
                        </tr>
                        <tr>
                          <td width=\"50%%\" style=\"vertical-align:top;padding:6px 8px;\">
                            <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:15px;line-height:1.5;\">
                              ⚡ <strong>Respuesta automática</strong><br>
                              Confirmación inmediata por email.
                            </div>
                          </td>
                          <td width=\"50%%\" style=\"vertical-align:top;padding:6px 8px;\">
                            <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:15px;line-height:1.5;\">
                              🛟 <strong>Nosotros nos ocupamos</strong><br>
                              Tú concéntrate en tu negocio.
                            </div>
                          </td>
                        </tr>
                      </table>
                      <div style=\"height:18px;\"></div>
                      <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:14px;color:#555;line-height:1.6;\">
                        ¿Prefieres que te llamemos? Responde a este correo o escríbenos por WhatsApp:
                        <a href=\"https://wa.me/34640369759\" style=\"color:#ff9800;text-decoration:none;\">+34 600 000 000</a>.
                      </div>
                      <div style=\"height:22px;\"></div>
                      <div style=\"text-align:center;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:12px;color:#9aa0a6;\">
                        © BeWorking • Málaga 
                      </div>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """, name);
    }

  public static String getAdminHtml(String name, String email, String phone, String gmailThreadLink, String mailtoLink, String waLink, String waWebLink) {
        return String.format("""
        <!doctype html>
        <html lang=\"es\">
        <head>
          <meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
          <title>Nuevo lead</title>
          <style>
            @media (max-width:600px){.container{width:100%%!important}.btn-row{padding-top:16px!important;text-align:center!important}.btn-group{width:100%%!important}.btn-td{display:block!important;width:100%%!important;padding:0 0 10px!important}.btn-td .btn{display:inline-block!important;margin:0 auto!important}.web-link{margin-top:6px}}
            .badge{display:inline-block;padding:6px 10px;border-radius:999px;background:#fff3e0;color:#e65100;font-weight:700;font-size:12px}
            .btn{background:#009e5c;color:#fff !important;text-decoration:none;padding:10px 14px;border-radius:10px;display:inline-block;font-weight:700}
            .row{padding:10px 0;border-bottom:1px dashed #eee}
            .label{color:#667085;font-size:12px;letter-spacing:.3px;text-transform:uppercase}
            .val{font-size:16px;font-weight:700;color:#111}
            .btn-row{padding-top:10px;text-align:center;}
            .btn-group{margin:0 auto;text-align:center;border-collapse:separate;border-spacing:0}
            .btn-td{padding:0 6px;text-align:center}
            .web-link{margin-top:10px;text-align:center;font-size:12px;color:#009e5c}
            .web-link a{color:#009e5c;text-decoration:none;font-weight:600}
          </style>
        </head>
        <body style=\"margin:0;padding:0;background:#f7f7f8;\">
          <table role=\"presentation\" width=\"100%%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">
            <tr>
              <td align=\"center\">
                <table class=\"container\" role=\"presentation\" width=\"620\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"width:620px;max-width:620px;margin:0 auto;\">
                  <tr>
                    <td style=\"background:linear-gradient(90deg,#ff9800 0%%,#ffb74d 100%%);padding:22px 24px;color:#fff;border-radius:14px 14px 0 0;\">
                      <div class=\"badge\">BeWorking</div>
                      <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:22px;font-weight:800;margin-top:10px;\">
                        Nuevo lead recibido
                      </div>
                      <div style=\"opacity:.9;font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;\">Seguimiento recomendado en &lt;15 min</div>
                    </td>
                  </tr>
                  <tr>
                    <td style=\"background:#fff;padding:18px 24px;border-radius:0 0 14px 14px;border:1px solid #eee;border-top:0;\">
                      <div class=\"row\">
                        <div class=\"label\">Nombre</div>
                        <div class=\"val\">%s</div>
                      </div>
                      <div class=\"row\">
                        <div class=\"label\">Email</div>
                        <div class=\"val\"><a href=\"mailto:%s\" style=\"color:#111;text-decoration:none;\">%s</a></div>
                      </div>
                      <div class=\"row\">
                        <div class=\"label\">Teléfono</div>
                        <div class=\"val\"><a href=\"tel:%s\" style=\"color:#111;text-decoration:none;\">%s</a></div>
                      </div>
                      <div class=\"row btn-row\" style=\"border-bottom:0;\">
                        <table role=\"presentation\" class=\"btn-group\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\">
                          <tr>
                            <td class=\"btn-td\">
                              <a class=\"btn\" href=\"%s\">Responder en Gmail</a>
                            </td>
                            <td class=\"btn-td\">
                              <a class=\"btn\" href=\"tel:%s\">Llamar ahora</a>
                            </td>
                            <td class=\"btn-td\">
                              <a class=\"btn\" href=\"%s\">WhatsApp</a>
                            </td>
                          </tr>
                        </table>
                        <div class=\"web-link\" style=\"font-size:12px;color:#555;\">
                          ¿No usas Gmail? <a href=\"%s\" style=\"color:#009e5c;text-decoration:none;font-weight:600;\">Responder por email</a>
                        </div>
                        <div class=\"web-link\">
                          <a href=\"%s\">Abrir en WhatsApp Web</a>
                        </div>
                      </div>

                      <div style=\"height:14px;\"></div>
                      <div style=\"font-family:Inter,Segoe UI,Roboto,Arial,sans-serif;font-size:12px;color:#9aa0a6;text-align:center;\">
                        Tip: registra el lead en CRM y etiqueta como <strong>Entrada web</strong>.
                      </div>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
          </table>
        </body>
        </html>
        """,
        name,
        email, email,
        phone, phone,
        gmailThreadLink,
        phone,
        waLink,
        mailtoLink,
        waWebLink
        );
    }
}
