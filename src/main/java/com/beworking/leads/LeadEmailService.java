package com.beworking.leads;

public class LeadEmailService {
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
                            <a href=\"https://be-working.com/#contacto\" class=\"btn\">
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
